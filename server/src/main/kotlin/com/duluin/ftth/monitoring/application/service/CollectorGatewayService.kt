package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.integration.AcknowledgedBngAction
import com.duluin.ftth.common.integration.BngActionDispatch
import com.duluin.ftth.common.integration.BngActionsAcknowledged
import com.duluin.ftth.common.integration.BngSessionsReported
import com.duluin.ftth.common.integration.CollectorConfigContributor
import com.duluin.ftth.common.integration.NasPollTarget
import com.duluin.ftth.common.integration.ReportedRadiusSession
import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.BngIngestResult
import com.duluin.ftth.contract.BngSessionBatch
import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.RadiusSessionReading
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import com.duluin.ftth.monitoring.domain.model.Collector
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import com.duluin.ftth.network.NetworkApi
import org.slf4j.LoggerFactory
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
    /**
     * Module lain (mis. bng) yang menitipkan target polling non-OLT lewat seam shared
     * kernel — monitoring memanggilnya tanpa mengimpor module itu, menghindari siklus.
     * Kosong bila tak ada module penyumbang.
     */
    private val collectorConfigContributors: List<CollectorConfigContributor> = emptyList(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handleHeartbeat(collectorId: UUID, heartbeat: CollectorHeartbeat): CollectorConfig {
        val collector = collectorRepository.findById(collectorId)
            ?: throw NotFoundException("Collector $collectorId tidak ditemukan")

        collector.recordHeartbeat(heartbeat.agentVersion, heartbeat.lastCycle?.let(::summarize))
        collectorRepository.save(collector)

        // ACK perintah BRAS yang dibawa denyut ini → module bng menuntaskannya (AFTER_COMMIT).
        publishBngActionAcks(collector.id, collector.tenantId, heartbeat)

        val config = buildConfig(collector)
        evaluateOltReachability(collector.tenantId, config, heartbeat)
        // Reachability OLT mungkin berubah → picu korelasi ulang insiden.
        events.publishEvent(AlarmsChangedEvent(collector.tenantId))
        return config
    }

    /**
     * Meneruskan hasil eksekusi perintah BRAS dari collector sebagai event shared
     * kernel; module bng menyerapnya (AFTER_COMMIT) untuk menuntaskan antreannya.
     * Monitoring hanya menyalurkan — tak mengenal makna perintahnya. [actionId] wire
     * yang bukan UUID valid dibuang diam-diam (agent nakal/rusak tak boleh menjatuhkan denyut).
     */
    private fun publishBngActionAcks(collectorId: UUID, tenantId: UUID, heartbeat: CollectorHeartbeat) {
        if (heartbeat.actionResults.isEmpty()) return
        val acked = heartbeat.actionResults.mapNotNull { result ->
            val actionId = runCatching { UUID.fromString(result.actionId) }.getOrNull() ?: return@mapNotNull null
            AcknowledgedBngAction(actionId, result.success, result.detail)
        }
        if (acked.isNotEmpty()) {
            events.publishEvent(BngActionsAcknowledged(tenantId, collectorId, acked))
        }
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
                    snmpPort = it.snmpPort,
                    snmpCommunity = it.snmpCommunity,
                )
            }

        return CollectorConfig(
            collectorName = collector.name,
            pollIntervalSeconds = collector.pollIntervalSeconds,
            targets = targets,
            paused = collector.status == CollectorStatus.PAUSED,
            nasTargets = collectContributedNasTargets(collector),
            bngActions = collectContributedBngActions(collector),
        )
    }

    /**
     * Mengumpulkan target BRAS dari seluruh contributor. Kegagalan satu contributor
     * di-log dan dilewati — konfigurasi OLT (jalur utama collector) tak boleh jatuh
     * hanya karena module penyumbang bermasalah.
     */
    private fun collectContributedNasTargets(collector: Collector): List<NasTarget> =
        collectorConfigContributors.flatMap { contributor ->
            try {
                contributor.nasTargetsFor(collector.id, collector.tenantId).map { it.toWire() }
            } catch (ex: Exception) {
                log.warn("Contributor {} gagal menyumbang target BRAS", contributor.javaClass.simpleName, ex)
                emptyList()
            }
        }

    private fun NasPollTarget.toWire() = NasTarget(
        nasId = nasId.toString(),
        name = name,
        vendor = vendor,
        host = host,
        adapterType = adapterType,
        expectedUsernames = expectedUsernames,
        // Kredensial kontrol diteruskan apa adanya (sudah terdekripsi), seperti snmpCommunity OLT.
        apiUsername = apiUsername,
        apiSecret = apiSecret,
        apiPort = apiPort,
        apiUseTls = apiUseTls,
        coaSecret = coaSecret,
    )

    /**
     * Mengumpulkan perintah BRAS yang menunggu dari seluruh contributor (jalur turun,
     * Phase 7c). Sama seperti target BRAS: kegagalan satu contributor di-log & dilewati
     * agar konfigurasi OLT (jalur utama) tak jatuh karena module penyumbang bermasalah.
     */
    private fun collectContributedBngActions(collector: Collector): List<BngActionCommand> =
        collectorConfigContributors.flatMap { contributor ->
            try {
                contributor.pendingBngActionsFor(collector.id, collector.tenantId).map { it.toWire() }
            } catch (ex: Exception) {
                log.warn("Contributor {} gagal menyumbang perintah BRAS", contributor.javaClass.simpleName, ex)
                emptyList()
            }
        }

    private fun BngActionDispatch.toWire() = BngActionCommand(
        actionId = actionId.toString(),
        nasId = nasId.toString(),
        kind = BngActionKind.valueOf(kind),
        username = username,
        downMbps = downMbps,
        upMbps = upMbps,
        groupname = groupname,
        password = password,
        rateLimit = rateLimit,
        simultaneousUse = simultaneousUse,
        fupGroupname = fupGroupname,
        fupRateLimit = fupRateLimit,
    )

    /**
     * Menerima batch sesi PPPoE dari collector dan menerbitkannya sebagai event shared
     * kernel; module bng yang menyerapnya (AFTER_COMMIT, lihat BngSessionListener).
     *
     * Monitoring sengaja TIDAK memvalidasi username terhadap akun bng — ia tak
     * mengenal data itu, dan menembusnya akan menjadikan monitoring→bng sebuah siklus.
     * Karena itu [BngIngestResult.unknownUsernames] selalu kosong dari sini;
     * rekonsiliasi akun tak dikenal terjadi di sisi bng (di-log). [nasId] wire yang
     * bukan UUID valid ditolak sebagai galat argumen.
     */
    fun handleBngSessions(collectorId: UUID, tenantId: UUID, batch: BngSessionBatch): BngIngestResult {
        val nasId = runCatching { UUID.fromString(batch.nasId) }.getOrNull()
            ?: throw IllegalArgumentException("nasId '${batch.nasId}' bukan UUID yang sah")

        events.publishEvent(
            BngSessionsReported(
                tenantId = tenantId,
                collectorId = collectorId,
                nasId = nasId,
                batchId = batch.batchId,
                collectedAt = batch.collectedAt,
                sessions = batch.sessions.map { it.toReported() },
            ),
        )
        return BngIngestResult(accepted = batch.sessions.size)
    }

    private fun RadiusSessionReading.toReported() = ReportedRadiusSession(
        username = username,
        online = online,
        framedIp = framedIp,
        nasIp = nasIp,
        sessionId = sessionId,
        callingStationId = callingStationId,
        uptimeSeconds = uptimeSeconds,
        inOctets = inOctets,
        outOctets = outOctets,
    )

    /** Ringkasan singkat siklus terakhir untuk ditampilkan di UI. */
    private fun summarize(cycle: com.duluin.ftth.contract.CycleReport): String = buildString {
        append("${cycle.targetsPolled} OLT ok")
        if (cycle.targetsFailed > 0) append(", ${cycle.targetsFailed} gagal")
        append(", ${cycle.readingsCollected} bacaan")
        cycle.failures.firstOrNull()?.let { append(" — ${it.oltCode}: ${it.message.take(120)}") }
    }
}
