package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import com.duluin.ftth.fieldservice.application.port.outbound.GpsPointRepository
import com.duluin.ftth.fieldservice.domain.model.*
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class GpsPointPersistenceAdapter(private val repository: GpsPointJpaRepository) : GpsPointRepository {
    override fun findByOperation(tenantId: UUID, namespace: String, operationKey: String) =
        repository.findByTenantIdAndOperationNamespaceAndOperationKey(tenantId, namespace, operationKey)?.toDomain()

    override fun save(point: GpsPoint): GpsPoint {
        repository.save(point.toEntity())
        return point
    }

    override fun deleteExpired(cutoff: Instant, legalHoldIds: Set<UUID>): List<UUID> {
        val expired = repository.findAllByServerReceivedAtBefore(cutoff).filterNot { it.id in legalHoldIds }
        repository.deleteAll(expired)
        return expired.map { it.id }
    }

    private fun GpsPoint.toEntity() = GpsPointJpaEntity(id, visitId, workSessionId, actorId, deviceId, longitude, latitude, accuracyMeters,
        provider, clientOccurredAt, serverReceivedAt, mock, purpose.name, retentionClass.name, decision.name, revision, operationNamespace, operationKey, payloadHash)

    private fun GpsPointJpaEntity.toDomain() = GpsPoint(id, tenantId ?: error("tenant missing"), visitId, workSessionId, actorId, deviceId,
        longitude, latitude, accuracyMeters, provider, clientOccurredAt, serverReceivedAt, mockIndicator, GpsPurpose.valueOf(purpose),
        GpsRetentionClass.valueOf(retentionClass), GpsReviewDecision.valueOf(decision), revision, operationNamespace, operationKey, payloadHash)
}
