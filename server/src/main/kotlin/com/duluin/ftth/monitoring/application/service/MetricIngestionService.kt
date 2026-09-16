package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.contract.IngestResult
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.OnuRef
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.customer.ObservationPath
import com.duluin.ftth.monitoring.adapter.outbound.persistence.UnassignedObservationStore
import com.duluin.ftth.monitoring.application.port.outbound.IngestBatchRepository
import com.duluin.ftth.monitoring.application.port.outbound.OnuMetricRepository
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.OnuMetricPoint
import com.duluin.ftth.network.NetworkApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.time.Instant
import java.util.Locale

/**
 * Menerima batch metrik dari collector: memetakannya ke ONU terdaftar, menyimpan
 * deret waktunya, memperbarui status ONU, dan menilai alarm.
 *
 * Seluruhnya dalam satu transaksi. Alasannya bukan kerapian melainkan kebenaran:
 * batch dicatat sebagai "sudah diterima" di transaksi yang sama dengan
 * penyimpanan metriknya. Kalau dipisah, kegagalan di tengah bisa meninggalkan
 * batch yang tercatat diterima padahal datanya tidak masuk — dan pengiriman
 * ulang collector akan ditolak sebagai duplikat.
 */
@Service
@Transactional
class MetricIngestionService(
    private val metricRepository: OnuMetricRepository,
    private val batchRepository: IngestBatchRepository,
    private val customerApi: CustomerApi,
    private val networkApi: NetworkApi,
    private val alarmEngine: AlarmEngine,
    private val discoveredOnuRecorder: DiscoveredOnuRecorder,
    private val events: ApplicationEventPublisher,
    private val observations: CustomerObservationApi,
    private val unassigned: UnassignedObservationStore,
    private val networkObservations: com.duluin.ftth.network.NetworkObservationApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingestRaw(collectorId: UUID, tenantId: UUID, batch: com.duluin.ftth.monitoring.application.port.inbound.CollectorObservationBatch): IngestResult {
        val now = Instant.now()
        validateBatchId(batch.batchId)
        if (batch.readings.size > MetricBatch.MAX_READINGS)
            throw com.duluin.ftth.common.domain.error.ValidationException("Batch exceeds maximum readings")
        if (!batchRepository.registerIfNew(batch.batchId, collectorId, tenantId, batch.readings.size))
            return IngestResult(0, emptyList(), true)
        val collected = com.duluin.ftth.monitoring.application.port.inbound.observationInstant(batch.collectedAt)
        val readings = mutableListOf<OnuReading>()
        val invalid = linkedSetOf<String>()
        val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
        for (reading in batch.readings) {
            val parsed = reading.parsed()
            if (parsed != null) readings += parsed else {
                unassigned.appendRaw(reading.serialNumber, null, "MALFORMED_TIMESTAMP", mapper.writeValueAsString(reading))
                invalid += reading.serialNumber.trim().uppercase(Locale.ROOT)
            }
        }
        val result = process(tenantId, readings, now, collected == null || !trustedTime(collected, now))
        return result.copy(unknownSerialNumbers = (result.unknownSerialNumbers + invalid).distinct().take(MAX_REPORTED_UNKNOWN))
    }

    fun ingest(collectorId: UUID, tenantId: UUID, batch: MetricBatch): IngestResult {
        val now = Instant.now()
        validateBatchId(batch.batchId)
        if (batch.readings.size > MetricBatch.MAX_READINGS) {
            // Collector nakal atau salah versi; ditolak agar tidak membebani ingestion.
            throw com.duluin.ftth.common.domain.error.ValidationException(
                "Batch berisi ${batch.readings.size} bacaan, maksimal ${MetricBatch.MAX_READINGS}",
            )
        }

        val isNew = batchRepository.registerIfNew(batch.batchId, collectorId, tenantId, batch.readings.size)
        if (!isNew) {
            log.debug("Batch {} sudah pernah diterima, diabaikan", batch.batchId)
            return IngestResult(accepted = 0, unknownSerialNumbers = emptyList(), duplicate = true)
        }

        return process(tenantId, batch.readings, now, !trustedTime(batch.collectedAt, now))
    }

    /**
     * Inti ingestion tanpa pembukuan batch: memetakan bacaan ke ONU terdaftar,
     * menyimpan deret waktunya, memperbarui status, menilai alarm, dan menangkap
     * ONU liar ke kotak masuk provisioning.
     *
     * Dipisah dari [ingest] karena dua jalur memasukinya:
     * - collector on-prem lewat [ingest], yang lebih dulu mendedup batch;
     * - polling SNMP server-side, yang memoll OLT sendiri dan TIDAK punya batch
     *   untuk didedup.
     *
     * Karena itu ia sengaja tidak menyentuh [batchRepository] maupun `collectorId`
     * (FK `ingest_batch.collector_id` yang tak ada padanannya di jalur server-side).
     * Tetap transaksional bersama pemanggil: penyimpanan metrik dan penilaian alarm
     * jatuh atau berhasil bersama-sama.
     */
    fun ingestReadings(tenantId: UUID, readings: List<OnuReading>): IngestResult {
        val now = Instant.now()
        return process(tenantId, readings.map { it.copy(observedAt = now) }, now, false,
            source = com.duluin.ftth.monitoring.domain.model.MetricSource.SERVER_INSTANT)
    }

    fun ingestServerReadings(tenantId: UUID, readings: List<OnuReading>, acquisition: ServerPollWindow): IngestResult =
        process(tenantId, readings.map { it.copy(observedAt = acquisition.startedAt) }, Instant.now(),
            acquisition.completedAt.isBefore(acquisition.startedAt), acquisition.completedAt,
            com.duluin.ftth.monitoring.domain.model.MetricSource.SERVER_POLL)

    private fun process(tenantId: UUID, readings: List<OnuReading>, now: Instant, untrustedBatch: Boolean, completedAt: Instant? = null,
        source: com.duluin.ftth.monitoring.domain.model.MetricSource = com.duluin.ftth.monitoring.domain.model.MetricSource.COLLECTOR): IngestResult {
        val serials = readings.mapTo(sortedSetOf()) { it.serialNumber.trim().uppercase(Locale.ROOT) }
        networkObservations.lockView()
        observations.lockEpisodes(serials)
        val oltIdsByCode = resolveOltIds(readings)
        val unknown = linkedSetOf<String>()
        val points = mutableListOf<OnuMetricPoint>()
        val current = linkedSetOf<String>()
        var changed = false
        for (reading in readings.sortedBy { it.observedAt }) {
            val serial = reading.serialNumber.trim().uppercase(Locale.ROOT)
            if (reading.pathProvenance == com.duluin.ftth.contract.OnuPathProvenance.UNVERIFIED_INDEX) {
                unassigned.append(reading, "UNVERIFIED_PON_IDENTITY")
                unknown += serial
                continue
            }
            val validTime = !untrustedBatch && trustedTime(reading.observedAt, now)
            if (validTime && reading.observedAt.isAfter(now)) {
                unassigned.append(reading, "FUTURE_OBSERVATION")
                unknown += serial
                continue
            }
            val storedTime = reading.observedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            val attribution = if (validTime) observations.resolveObservation(serial, storedTime,
                ObservationPath(oltIdsByCode[reading.oltCode.uppercase(Locale.ROOT)], reading.oltCode, reading.ponPortLabel)) else null
            val episode = attribution?.episode
            if (episode != null && completedAt != null) {
                val completed = observations.resolveObservation(serial, completedAt,
                    ObservationPath(oltIdsByCode[reading.oltCode.uppercase(Locale.ROOT)], reading.oltCode, reading.ponPortLabel))
                if (completed.episode?.onu?.id != episode.onu.id || completed.topologyRevision != attribution.topologyRevision ||
                    completed.networkEdgeIds != attribution.networkEdgeIds) {
                    unassigned.append(reading, "POLL_SPANS_TRANSITION")
                    unknown += serial
                    continue
                }
            }
            if (episode == null) {
                unassigned.append(reading, attribution?.reason ?: "UNTRUSTED_TIMESTAMP")
                unknown += serial
                if (validTime && attribution?.reason == "NO_EPISODE_AT_TIME" && observations.currentEpisode(serial) == null)
                    discoveredOnuRecorder.capture(tenantId, listOf(reading), oltIdsByCode)
                continue
            }
            val onu = episode.onu
            points += OnuMetricPoint(storedTime, tenantId, onu.id, oltIdsByCode[reading.oltCode.uppercase(Locale.ROOT)],
                reading.status.name, reading.rxPowerDbm, reading.txPowerDbm, reading.uptimeSeconds, reading.distanceMeters,
                reading.lastDownCause?.name, reading.lastOffAt, reading.lastOnAt,
                com.duluin.ftth.monitoring.domain.model.MetricAttribution(source, now, episode.episodeRevision, episode.assignmentId,
                    episode.assignmentRevision, requireNotNull(attribution.topologyRevision), attribution.networkEdgeIds))
            if (observations.advanceLiveObservation(episode, storedTime)) {
                customerApi.recordObservedOnuStatuses(mapOf(onu.id to reading.status.toOnuStatus()))
                evaluateAlarms(tenantId, reading, onu)
                changed = true
                current += serial
            }
        }
        if (points.isNotEmpty()) metricRepository.saveAll(points)
        if (changed) events.publishEvent(AlarmsChangedEvent(tenantId))
        if (current.isNotEmpty()) discoveredOnuRecorder.resolveKnown(current)
        return IngestResult(points.size, unknown.take(MAX_REPORTED_UNKNOWN), false)
    }

    private fun trustedTime(time: Instant, now: Instant): Boolean =
        com.duluin.ftth.customer.ObservationTimePolicy.rejection(time, now) == null

    private fun validateBatchId(id: String) {
        if (id.isBlank() || id.length > 64) throw com.duluin.ftth.common.domain.error.ValidationException("Batch id must contain 1-64 characters")
    }

    /**
     * Menilai seluruh jenis alarm untuk satu ONU.
     *
     * Perhatikan alarm redaman hanya dinilai saat ONU ONLINE: ONU yang mati tidak
     * melaporkan redaman, dan menilainya sebagai "redaman hilang" akan menerbitkan
     * alarm redaman palsu berdampingan dengan alarm mati yang sebenarnya.
     */
    private fun evaluateAlarms(tenantId: UUID, reading: OnuReading, onu: OnuRef) {
        val label = "${onu.serialNumber} (${onu.customerName})"

        alarmEngine.evaluate(
            tenantId, AlarmKind.ONU_LOS, onu.id, label,
            conditionPresent = reading.status == OnuOperationalStatus.LOS,
            messageBuilder = { "ONU $label kehilangan sinyal — kemungkinan fiber putus" },
        )

        alarmEngine.evaluate(
            tenantId, AlarmKind.ONU_OFFLINE, onu.id, label,
            conditionPresent = reading.status == OnuOperationalStatus.OFFLINE,
            messageBuilder = { "ONU $label tidak terhubung" },
        )

        val online = reading.status == OnuOperationalStatus.ONLINE
        val rx = reading.rxPowerDbm

        alarmEngine.evaluate(
            tenantId, AlarmKind.ONU_LOW_RX, onu.id, label,
            conditionPresent = online && rx != null,
            value = rx.takeIf { online },
            messageBuilder = { severity -> "Redaman ONU $label $rx dBm ($severity)" },
        )

        alarmEngine.evaluate(
            tenantId, AlarmKind.ONU_HIGH_RX, onu.id, label,
            conditionPresent = online && rx != null,
            value = rx.takeIf { online },
            messageBuilder = { severity -> "Redaman ONU $label terlalu kuat: $rx dBm ($severity)" },
        )
    }

    /** Collector mengirim kode OLT; server memetakannya ke id inventory. */
    private fun resolveOltIds(readings: List<OnuReading>): Map<String, UUID> =
        readings.mapTo(HashSet()) { it.oltCode.uppercase() }
            .mapNotNull { code -> networkApi.findOltByCode(code)?.let { code to it.id } }
            .toMap()

    private fun OnuOperationalStatus.toOnuStatus(): String = when (this) {
        OnuOperationalStatus.ONLINE -> "ONLINE"
        OnuOperationalStatus.OFFLINE -> "OFFLINE"
        OnuOperationalStatus.LOS -> "LOS"
        // ONU yang dikenali OLT tapi belum terotorisasi belum tentu bermasalah;
        // status terdaftarnya dibiarkan apa adanya.
        OnuOperationalStatus.UNKNOWN -> "PENDING"
    }

    private companion object {
        const val MAX_REPORTED_UNKNOWN = 50
    }
}
