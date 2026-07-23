package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.application.port.outbound.IngestBatchRepository
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Mengangkat alarm untuk collector yang berhenti melapor, sekaligus membersihkan
 * catatan deduplikasi lama.
 *
 * Ini menutup lubang yang mudah luput: seluruh deteksi gangguan lain bergantung
 * pada data yang DIKIRIM collector. Kalau collector-nya sendiri mati, tidak ada
 * data yang datang, tidak ada alarm yang terpicu, dan dashboard tampak tenang
 * justru saat sistem sedang buta. Hanya pemeriksaan terjadwal seperti inilah
 * yang bisa melihat ketiadaan data.
 */
@Component
class SilentCollectorWatchdog(
    private val collectorRepository: CollectorRepository,
    private val batchRepository: IngestBatchRepository,
    private val evaluator: SilentCollectorEvaluator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Berjalan lintas tenant, jadi tenant context dipasang per collector.
     * Kegagalan satu tenant tidak menghentikan pemeriksaan tenant lain.
     */
    @Scheduled(fixedDelayString = "\${ftth.monitoring.watchdog-interval:PT2M}")
    fun checkCollectors() {
        val now = Instant.now()
        collectorRepository.findAllActive().forEach { collector ->
            runCatching {
                TenantContext.runAs(collector.tenantId) {
                    evaluator.evaluate(collector.tenantId, collector.id, collector.name, collector.isSilent(now), now)
                }
            }.onFailure {
                log.warn("Pemeriksaan collector {} gagal: {}", collector.name, it.message)
            }
        }
    }

    /**
     * Jendela dedup hanya perlu menutupi percobaan ulang collector, yang berumur
     * menit. Menyimpannya lebih lama hanya menumpuk baris tanpa guna.
     *
     * Dipanggil scheduler dari luar kelas, sehingga `@Transactional` di sini
     * benar-benar melewati proxy — berbeda dengan [checkCollectors] yang harus
     * mendelegasikan ke komponen terpisah.
     */
    @Scheduled(fixedDelayString = "\${ftth.monitoring.batch-cleanup-interval:PT1H}")
    @Transactional
    fun purgeOldBatches() {
        val removed = batchRepository.deleteOlderThan(Instant.now().minus(Duration.ofHours(6)))
        if (removed > 0) log.debug("{} catatan batch lama dibersihkan", removed)
    }
}

/**
 * Penilaian satu collector dalam transaksinya sendiri.
 *
 * Komponen terpisah, bukan method privat di [SilentCollectorWatchdog]: Spring
 * menerapkan `@Transactional` lewat proxy, jadi pemanggilan dari dalam kelas yang
 * sama TIDAK akan pernah dibungkus transaksi dan anotasinya diam-diam tak
 * berefek. REQUIRES_NEW menjaga agar satu collector yang bermasalah tidak
 * menggagalkan seluruh putaran pemeriksaan.
 */
@Component
class SilentCollectorEvaluator(
    private val alarmEngine: AlarmEngine,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun evaluate(tenantId: UUID, collectorId: UUID, name: String, silent: Boolean, now: Instant) {
        alarmEngine.evaluate(
            tenantId = tenantId,
            kind = AlarmKind.COLLECTOR_SILENT,
            entityId = collectorId,
            entityLabel = name,
            conditionPresent = silent,
            messageBuilder = { "Collector '$name' berhenti melapor — pemantauan jaringan sedang buta" },
            at = now,
        )
        // Alarm collector-membisu mungkin berubah → picu korelasi ulang insiden.
        events.publishEvent(com.duluin.ftth.monitoring.AlarmsChangedEvent(tenantId))
    }
}
