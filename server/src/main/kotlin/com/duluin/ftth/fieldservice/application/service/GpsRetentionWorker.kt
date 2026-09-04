package com.duluin.ftth.fieldservice.application.service

import com.duluin.ftth.fieldservice.application.port.outbound.GpsLegalHoldPort
import com.duluin.ftth.fieldservice.application.port.outbound.GpsPointRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class GpsDeletionEvidence(val tenantId: UUID, val pointIds: List<UUID>, val deletedAt: Instant, val retentionClass: String)

class GpsRetentionWorker(
    private val points: GpsPointRepository,
    private val legalHolds: GpsLegalHoldPort,
    private val clock: () -> Instant = Instant::now,
) {
    fun purge(tenantId: UUID, retention: Duration = Duration.ofDays(90)): GpsDeletionEvidence {
        val now = clock()
        val deleted = points.deleteExpired(now.minus(retention), legalHolds.heldPointIds(tenantId))
        return GpsDeletionEvidence(tenantId, deleted, now, "GPS_90_DAYS")
    }
}
