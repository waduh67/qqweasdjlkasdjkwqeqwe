package com.duluin.ftth.fieldservice.adapter.inbound.web

import com.duluin.ftth.fieldservice.domain.model.AttendanceDecision
import java.time.Instant
import java.util.UUID

data class CreateVisitRequest(
    val orderId: UUID,
    val workOrderId: UUID,
    val technicianId: UUID,
    val plannedAt: Instant,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val revision: Long,
)

data class AttendanceRequest(
    val decision: AttendanceDecision,
    val reason: String?,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val revision: Long,
)

data class VisitResponse(
    val id: UUID,
    val state: String,
    val revision: Long,
    val attendanceDecision: AttendanceDecision?,
    val serverReceivedAt: Instant?,
)

data class CaptureGpsRequest(
    val visitId: UUID,
    val workSessionId: UUID,
    val deviceId: UUID,
    val longitude: Double,
    val latitude: Double,
    val accuracyMeters: Double,
    val provider: String,
    val clientOccurredAt: Instant,
    val mock: Boolean,
    val purpose: String,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val revision: Long,
)

data class GpsCaptureResponse(
    val pointId: UUID?,
    val decision: String,
    val serverReceivedAt: Instant?,
)
