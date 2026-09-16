package com.duluin.ftth.monitoring.application.port.inbound

import com.duluin.ftth.contract.OnuDownCause
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import java.time.Instant
import java.time.format.DateTimeParseException

data class CollectorObservationBatch(val batchId: String, val collectedAt: String, val readings: List<CollectorObservationReading>)

data class CollectorObservationReading(
    val serialNumber: String, val oltCode: String, val ponPortLabel: String?, val status: OnuOperationalStatus,
    val rxPowerDbm: Double?, val txPowerDbm: Double?, val uptimeSeconds: Long?, val distanceMeters: Int?,
    val observedAt: String, val lastDownCause: OnuDownCause? = null, val lastOffAt: String? = null, val lastOnAt: String? = null,
) {
    fun parsed(): OnuReading? {
        val observed = observationInstant(observedAt) ?: return null
        val off = lastOffAt?.let { observationInstant(it) ?: return null }
        val on = lastOnAt?.let { observationInstant(it) ?: return null }
        return OnuReading(serialNumber, oltCode, ponPortLabel, status, rxPowerDbm, txPowerDbm, uptimeSeconds, distanceMeters,
            observed, lastDownCause, off, on)
    }
}

fun observationInstant(value: String): Instant? = try { Instant.parse(value) } catch (_: DateTimeParseException) { null }
