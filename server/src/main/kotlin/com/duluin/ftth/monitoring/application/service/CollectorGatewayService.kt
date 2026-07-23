package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import com.duluin.ftth.monitoring.domain.model.Collector
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import com.duluin.ftth.network.NetworkApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Melayani denyut collector: mencatat bahwa ia hidup, lalu mengembalikan
 * konfigurasi polling terbaru.
 *
 * Konfigurasi dikirim tiap denyut — bukan sekali saat pendaftaran — supaya
 * perubahan yang dilakukan operator di UI (menambah OLT, mengubah interval,
 * menjeda) sampai ke lapangan pada siklus berikutnya tanpa siapa pun perlu
 * masuk ke mesin collector.
 */
@Service
@Transactional
class CollectorGatewayService(
    private val collectorRepository: CollectorRepository,
    private val networkApi: NetworkApi,
    private val alarmEngine: AlarmEngine,
    private val events: ApplicationEventPublisher,
) {
    fun handleHeartbeat(collectorId: UUID, heartbeat: CollectorHeartbeat): CollectorConfig {
        val collector = collectorRepository.findById(collectorId)
            ?: throw NotFoundException("Collector $collectorId tidak ditemukan")

        collector.recordHeartbeat(heartbeat.agentVersion, heartbeat.lastCycle?.let(::summarize))
        collectorRepository.save(collector)

        val config = buildConfig(collector)
        evaluateOltReachability(collector.tenantId, config, heartbeat)
        // Reachability OLT mungkin berubah → picu korelasi ulang insiden.
        events.publishEvent(AlarmsChangedEvent(collector.tenantId))
        return config
    }

    /**
     * Mengangkat/menutup alarm OLT tak-terjangkau dari laporan siklus collector.
     *
     * Dinilai terhadap SELURUH OLT yang ditugaskan ke collector, bukan hanya yang
     * gagal: OLT yang tidak ada di daftar kegagalan berarti pulih, sehingga
     * alarmnya ditutup otomatis. Tanpa membandingkan ke daftar lengkap, alarm OLT
     * yang sudah sehat akan menggantung selamanya.
     */
    private fun evaluateOltReachability(tenantId: UUID, config: CollectorConfig, heartbeat: CollectorHeartbeat) {
        val cycle = heartbeat.lastCycle ?: return // siklus pertama belum punya data
        val failureById = cycle.failures.associateBy { it.oltId }
        config.targets.forEach { target ->
            val oltId = runCatching { UUID.fromString(target.oltId) }.getOrNull() ?: return@forEach
            val failure = failureById[target.oltId]
            alarmEngine.evaluate(
                tenantId = tenantId,
                kind = AlarmKind.OLT_UNREACHABLE,
                entityId = oltId,
                entityLabel = target.oltCode,
                conditionPresent = failure != null,
                messageBuilder = {
                    "OLT ${target.oltCode} tidak bisa dihubungi collector — ${failure?.message ?: "gangguan jaringan"}"
                },
            )
        }
    }

    private fun buildConfig(collector: Collector): CollectorConfig {
        // Penugasan kosong berarti "semua OLT tenant ini" — konfigurasi bawaan
        // yang benar untuk ISP satu POP, yang merupakan mayoritas.
        val assigned = collectorRepository.findAssignedOltIds(collector.id)
            .ifEmpty { networkApi.listAllOltIds() }

        val targets = networkApi.findPollingTargets(assigned)
            // OLT tanpa alamat manajemen tidak bisa dihubungi; mengirimkannya
            // hanya menghasilkan kegagalan yang membingungkan di log collector.
            .filter { it.pollable }
            .map {
                OltTarget(
                    oltId = it.id.toString(),
                    oltCode = it.code,
                    vendor = it.vendor,
                    host = it.host!!,
                    snmpCommunity = it.snmpCommunity,
                )
            }

        return CollectorConfig(
            collectorName = collector.name,
            pollIntervalSeconds = collector.pollIntervalSeconds,
            targets = targets,
            paused = collector.status == CollectorStatus.PAUSED,
        )
    }

    /** Ringkasan singkat siklus terakhir untuk ditampilkan di UI. */
    private fun summarize(cycle: com.duluin.ftth.contract.CycleReport): String = buildString {
        append("${cycle.targetsPolled} OLT ok")
        if (cycle.targetsFailed > 0) append(", ${cycle.targetsFailed} gagal")
        append(", ${cycle.readingsCollected} bacaan")
        cycle.failures.firstOrNull()?.let { append(" — ${it.oltCode}: ${it.message.take(120)}") }
    }
}
