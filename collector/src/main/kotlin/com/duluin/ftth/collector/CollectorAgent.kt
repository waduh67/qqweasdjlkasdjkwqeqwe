package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.AdapterRegistry
import com.duluin.ftth.collector.adapter.ProbeResult
import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.CycleReport
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.contract.TargetFailure
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loop utama agent: denyut → ambil konfigurasi → polling tiap OLT → kirim hasil.
 *
 * Tiga sifat yang disengaja, semuanya karena agent ini hidup di jaringan yang
 * tidak bisa kita kendalikan:
 *
 * 1. **Satu OLT bermasalah tidak menghentikan yang lain.** Kegagalan dikumpulkan
 *    per target dan dilaporkan lewat denyut, bukan dilempar ke atas.
 * 2. **Konfigurasi selalu datang dari server.** Tidak ada state polling yang
 *    disimpan lokal, sehingga collector yang di-restart langsung benar.
 * 3. **Batch punya identitas.** Bila pengiriman gagal, batch yang sama dikirim
 *    ulang dengan id yang sama agar server bisa membuang duplikat — koneksi ISP
 *    yang putus-nyambung tidak boleh melipatgandakan metrik.
 */
class CollectorAgent(
    private val client: ServerClient,
    private val registry: AdapterRegistry,
    private val agentVersion: String,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(true)

    fun stop() = running.set(false)

    /**
     * Satu putaran lalu berhenti: denyut, polling, kirim.
     *
     * Dipakai pengujian end-to-end dan pemasangan bergaya cron, di mana penjadwalan
     * ditangani systemd timer alih-alih loop internal.
     */
    fun runOnce(): CycleReport? {
        val config = client.heartbeat(CollectorHeartbeat(agentVersion = agentVersion))
        if (config.paused) {
            log.info("Collector '{}' sedang dijeda server", config.collectorName)
            return null
        }
        return runCycle(config)
    }

    fun run() {
        var lastCycle: CycleReport? = null
        while (running.get()) {
            val config = try {
                client.heartbeat(CollectorHeartbeat(agentVersion = agentVersion, lastCycle = lastCycle))
            } catch (ex: ServerRejectedException) {
                if (ex.permanent) {
                    // API key salah atau collector dinonaktifkan: mencoba lagi
                    // dengan cepat hanya membanjiri log dan server.
                    log.error("Server menolak collector secara permanen, berhenti: {}", ex.message)
                    return
                }
                log.warn("Denyut gagal, coba lagi: {}", ex.message)
                sleeper(RETRY_DELAY_MILLIS)
                continue
            } catch (ex: Exception) {
                log.warn("Server tidak bisa dihubungi ({}), coba lagi", ex.message)
                sleeper(RETRY_DELAY_MILLIS)
                continue
            }

            lastCycle = if (config.paused) {
                log.info("Collector '{}' sedang dijeda server", config.collectorName)
                null
            } else {
                runCycle(config)
            }

            sleeper(config.pollIntervalSeconds.coerceAtLeast(MIN_POLL_SECONDS) * 1_000L)
        }
    }

    /** Menjalankan satu putaran polling untuk seluruh OLT. */
    internal fun runCycle(config: CollectorConfig): CycleReport {
        val startedAt = clock()
        val failures = mutableListOf<TargetFailure>()
        val readings = mutableListOf<OnuReading>()

        for (target in config.targets) {
            try {
                readings += pollTarget(target)
            } catch (ex: Exception) {
                log.warn("Polling {} gagal: {}", target.oltCode, ex.message)
                failures += TargetFailure(target.oltId, target.oltCode, ex.message ?: "galat tidak diketahui")
            }
        }

        readings.chunked(MetricBatch.MAX_READINGS).forEach(::deliver)

        val report = CycleReport(
            startedAt = startedAt,
            finishedAt = clock(),
            targetsPolled = config.targets.size - failures.size,
            targetsFailed = failures.size,
            readingsCollected = readings.size,
            failures = failures,
        )
        log.info(
            "Siklus selesai: {} OLT, {} gagal, {} bacaan",
            report.targetsPolled, report.targetsFailed, report.readingsCollected,
        )
        return report
    }

    private fun pollTarget(target: OltTarget): List<OnuReading> {
        val adapter = registry.forVendor(target.vendor)
            ?: throw IllegalStateException(
                "Vendor ${target.vendor} belum didukung (tersedia: ${registry.supportedVendors.joinToString()})",
            )

        // Probe dulu supaya OLT yang mati dilaporkan cepat, bukan setelah walk
        // SNMP-nya kehabisan waktu satu per satu.
        when (val probe = adapter.probe(target)) {
            is ProbeResult.Unreachable -> throw IllegalStateException("Tidak bisa dihubungi: ${probe.reason}")
            is ProbeResult.Reachable -> log.debug("{} menjawab dalam {} ms", target.oltCode, probe.roundTripMillis)
        }
        return adapter.pollOnus(target)
    }

    /**
     * Mengirim satu batch dengan percobaan ulang. Id batch dipertahankan antar
     * percobaan supaya server bisa mengenali kiriman yang sama.
     */
    private fun deliver(readings: List<OnuReading>) {
        val batch = MetricBatch(
            batchId = UUID.randomUUID().toString(),
            collectedAt = clock(),
            readings = readings,
        )

        repeat(MAX_DELIVERY_ATTEMPTS) { attempt ->
            try {
                val result = client.pushMetrics(batch)
                if (result.unknownSerialNumbers.isNotEmpty()) {
                    log.info(
                        "{} ONU tidak dikenal server (kandidat perangkat liar): {}",
                        result.unknownSerialNumbers.size,
                        result.unknownSerialNumbers.take(5).joinToString(),
                    )
                }
                return
            } catch (ex: ServerRejectedException) {
                if (ex.permanent) {
                    log.error("Batch ditolak permanen, dibuang: {}", ex.message)
                    return
                }
                log.warn("Pengiriman batch gagal (percobaan {}): {}", attempt + 1, ex.message)
                sleeper(RETRY_DELAY_MILLIS)
            } catch (ex: Exception) {
                log.warn("Pengiriman batch gagal (percobaan {}): {}", attempt + 1, ex.message)
                sleeper(RETRY_DELAY_MILLIS)
            }
        }
        // Metrik yang gagal terkirim sengaja tidak diantre ke disk: data optik
        // berumur pendek nilainya, dan siklus berikutnya sudah membawa yang baru.
        log.error("Batch {} dibuang setelah {} percobaan", batch.batchId, MAX_DELIVERY_ATTEMPTS)
    }

    private companion object {
        const val RETRY_DELAY_MILLIS = 5_000L
        const val MAX_DELIVERY_ATTEMPTS = 3
        const val MIN_POLL_SECONDS = 30
    }
}
