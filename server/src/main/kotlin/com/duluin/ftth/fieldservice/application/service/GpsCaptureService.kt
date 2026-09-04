package com.duluin.ftth.fieldservice.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.fieldservice.application.port.outbound.*
import com.duluin.ftth.fieldservice.application.port.inbound.CaptureGpsUseCase
import com.duluin.ftth.fieldservice.domain.model.*
import java.time.Instant
import java.util.UUID

class GpsCaptureService(
    private val points: GpsPointRepository,
    private val assigned: GpsActorAssignmentPort,
    private val exactAccess: GpsExactAccessPort,
    private val accessAudit: GpsAccessAuditPort = GpsAccessAuditPort { },
) : CaptureGpsUseCase {
    override
    fun capture(command: CaptureGpsCommand, serverReceivedAt: Instant): GpsCaptureResult {
        val replay = points.findByOperation(command.tenantId, command.operationNamespace, command.operationKey)
        if (replay != null) {
            if (replay.payloadHash != command.payloadHash) throw IllegalStateException("GPS operation payload conflict")
            return GpsCaptureResult(replay.decision, replay)
        }
        if (!assigned.isAssigned(command.visitId, command.actorId)) throw AccessDeniedException("Actor is not assigned to visit")
        val decision = command.validate(serverReceivedAt) ?: GpsReviewDecision.ACCEPTED
        if (decision == GpsReviewDecision.REJECTED) return GpsCaptureResult(decision, null)
        val point = GpsPoint(UUID.randomUUID(), command.tenantId, command.visitId, command.workSessionId, command.actorId, command.deviceId,
            command.longitude, command.latitude, command.accuracyMeters, command.provider, command.clientOccurredAt, serverReceivedAt, command.mock,
            command.purpose, GpsRetentionClass.GPS_90_DAYS, decision, command.revision, command.operationNamespace, command.operationKey, command.payloadHash)
        return GpsCaptureResult(decision, points.save(point))
    }

    fun exact(point: GpsPoint, actorId: UUID, accessedAt: Instant): GpsPoint {
        if (!exactAccess.canReadExact(actorId, point.tenantId)) throw AccessDeniedException("Exact GPS access denied")
        accessAudit.record(GpsAccessAudit(point.tenantId, actorId, point.id, point.purpose.name, true, accessedAt))
        return point
    }
}

data class GpsCaptureResult(val decision: GpsReviewDecision, val point: GpsPoint?)
