package com.duluin.ftth.fieldservice.application.port.outbound

import com.duluin.ftth.fieldservice.domain.model.GpsPoint
import java.time.Instant
import java.util.UUID

interface GpsPointRepository {
    fun findByOperation(tenantId: UUID, namespace: String, operationKey: String): GpsPoint?
    fun save(point: GpsPoint): GpsPoint
    fun deleteExpired(cutoff: Instant, legalHoldIds: Set<UUID>): List<UUID>
}

class InMemoryGpsPointRepository : GpsPointRepository {
    private val points = mutableListOf<GpsPoint>()
    override fun findByOperation(tenantId: UUID, namespace: String, operationKey: String) =
        points.firstOrNull { it.tenantId == tenantId && it.operationNamespace == namespace && it.operationKey == operationKey }
    override fun save(point: GpsPoint): GpsPoint { points += point; return point }
    override fun deleteExpired(cutoff: Instant, legalHoldIds: Set<UUID>): List<UUID> {
        val deleted = points.filter { it.serverReceivedAt < cutoff && it.id !in legalHoldIds }.map { it.id }
        points.removeIf { it.id in deleted }
        return deleted
    }
}

fun interface GpsActorAssignmentPort { fun isAssigned(visitId: UUID, actorId: UUID): Boolean }
fun interface GpsExactAccessPort { fun canReadExact(actorId: UUID, tenantId: UUID): Boolean }
