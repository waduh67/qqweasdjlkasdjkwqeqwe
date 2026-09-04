package com.duluin.ftth.fieldservice.domain.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class GpsPurpose { ONSITE, ATTENDANCE }
enum class GpsReviewDecision { ACCEPTED, REVIEW_REQUIRED, REJECTED }
enum class GpsRetentionClass { GPS_90_DAYS }

data class CaptureGpsCommand(
    val tenantId: UUID,
    val visitId: UUID,
    val workSessionId: UUID,
    val actorId: UUID,
    val deviceId: UUID,
    val longitude: Double,
    val latitude: Double,
    val accuracyMeters: Double,
    val provider: String,
    val clientOccurredAt: Instant,
    val mock: Boolean,
    val purpose: GpsPurpose,
    val operationNamespace: String,
    val operationKey: String,
    val payloadHash: String,
    val revision: Long,
)

data class GpsPoint(
    val id: UUID,
    val tenantId: UUID,
    val visitId: UUID,
    val workSessionId: UUID,
    val actorId: UUID,
    val deviceId: UUID,
    val longitude: Double,
    val latitude: Double,
    val accuracyMeters: Double,
    val provider: String,
    val clientOccurredAt: Instant,
    val serverReceivedAt: Instant,
    val mock: Boolean,
    val purpose: GpsPurpose,
    val retentionClass: GpsRetentionClass,
    val decision: GpsReviewDecision,
    val revision: Long,
    val operationNamespace: String,
    val operationKey: String,
    val payloadHash: String,
)

data class CustomerGpsProjection(val visitId: UUID, val hasOnsiteSignal: Boolean, val exactLocation: String? = null) {
    companion object {
        fun from(point: GpsPoint): CustomerGpsProjection = CustomerGpsProjection(point.visitId, point.decision == GpsReviewDecision.ACCEPTED)
    }
}

internal fun CaptureGpsCommand.validate(receivedAt: Instant): GpsReviewDecision? {
    if (!longitude.isFinite() || !latitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) {
        return GpsReviewDecision.REJECTED
    }
    if (accuracyMeters.isNaN() || accuracyMeters <= 0 || provider.isBlank()) return GpsReviewDecision.REJECTED
    if (clientOccurredAt.isAfter(receivedAt.plusSeconds(15 * 60))) return GpsReviewDecision.REJECTED
    return if (Duration.between(clientOccurredAt, receivedAt) > Duration.ofHours(72) || accuracyMeters > 100.0) {
        GpsReviewDecision.REVIEW_REQUIRED
    } else null
}
