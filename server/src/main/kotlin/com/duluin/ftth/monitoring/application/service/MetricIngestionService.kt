package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.contract.IngestResult
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.OnuRef
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(collectorId: UUID, tenantId: UUID, batch: MetricBatch): IngestResult {
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

        val serials = batch.readings.mapTo(HashSet()) { it.serialNumber.trim().uppercase() }
        val knownOnus = customerApi.findOnusBySerialNumbers(serials).associateBy { it.serialNumber }
        val oltIdsByCode = resolveOltIds(batch.readings)

        val unknown = serials.filterNot { it in knownOnus }
        val matched = batch.readings.mapNotNull { reading ->
            knownOnus[reading.serialNumber.trim().uppercase()]?.let { onu -> reading to onu }
        }

        if (matched.isNotEmpty()) {
            metricRepository.saveAll(
                matched.map { (reading, onu) ->
                    OnuMetricPoint(
                        time = reading.observedAt,
                        tenantId = tenantId,
                        onuId = onu.id,
                        oltId = oltIdsByCode[reading.oltCode.uppercase()],
                        status = reading.status.name,
                        rxPowerDbm = reading.rxPowerDbm,
                        txPowerDbm = reading.txPowerDbm,
                        uptimeSeconds = reading.uptimeSeconds,
                        distanceMeters = reading.distanceMeters,
                    )
                },
            )

            customerApi.recordObservedOnuStatuses(
                matched.associate { (reading, onu) -> onu.id to reading.status.toOnuStatus() },
            )

            matched.forEach { (reading, onu) -> evaluateAlarms(tenantId, reading, onu) }
            // Alarm mungkin berubah → picu korelasi ulang insiden untuk tenant ini,
            // sekali setelah seluruh batch dinilai (bukan per ONU).
            events.publishEvent(AlarmsChangedEvent(tenantId))

            // Serial yang kini dikenal tapi masih menggantung di kotak masuk
            // (didaftarkan lewat jalur lain) dituntaskan sendiri.
            discoveredOnuRecorder.resolveKnown(knownOnus.keys)
        }

        if (unknown.isNotEmpty()) {
            // ONU liar ditangkap ke kotak masuk provisioning, bukan sekadar dicatat log:
            // operator bisa menuntaskannya jadi pelanggan tanpa mengetik ulang serial.
            val unknownReadings = batch.readings.filterNot { it.serialNumber.trim().uppercase() in knownOnus }
            discoveredOnuRecorder.capture(tenantId, unknownReadings, oltIdsByCode)
            log.info("{} serial ONU tidak dikenal ditangkap ke kotak masuk pada batch {}", unknown.size, batch.batchId)
        }
        return IngestResult(
            accepted = matched.size,
            // Dibatasi agar respons tidak membengkak saat OLT baru dipasang dan
            // seluruh ONU-nya belum didaftarkan.
            unknownSerialNumbers = unknown.take(MAX_REPORTED_UNKNOWN),
            duplicate = false,
        )
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
