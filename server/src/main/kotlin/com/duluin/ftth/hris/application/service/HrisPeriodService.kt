package com.duluin.ftth.hris.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisPeriodJpaEntity
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisPeriodJpaRepository
import com.duluin.ftth.hris.application.port.HrisEventStore
import com.duluin.ftth.hris.application.port.HrisOutcomeStore
import com.duluin.ftth.hris.application.port.HrisOutcomePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class HrisPeriodService(private val current: CurrentUserProvider, private val periods: HrisPeriodJpaRepository, private val events: HrisEventStore, private val outcomes: HrisOutcomeStore) {
    @Transactional
    fun create(from: LocalDate, to: LocalDate, operationKey: String, payloadHash: String): UUID {
        if (to.isBefore(from)) throw ConflictException("period dates are reversed")
        val actor = current.current()
        val prior = outcomes.find(actor.tenantId, "attendance.period.create", operationKey)
        if (prior != null) { if (prior.payloadHash != payloadHash || prior.payload.targetId != prior.payload.resultId || prior.payload.fromDate != from || prior.payload.toDate != to) throw ConflictException("operation identity conflicts"); return prior.payload.resultId }
        val entity = HrisPeriodJpaEntity().apply { validFrom = from; validTo = to }
        periods.save(entity); outcomes.record(actor.tenantId, "attendance.period.create", operationKey, payloadHash, HrisOutcomePayload(entity.id, actor.userId, entity.id, "period", "OPEN", from, to)); events.append(actor.tenantId, entity.id, "AttendancePeriodCreated", 1, Instant.now(), mapOf("from" to from.toString(), "to" to to.toString()))
        return entity.id
    }
    @Transactional
    fun close(id: UUID, reason: String, operationKey: String, payloadHash: String) = decide(id, reason, operationKey, payloadHash, false)
    @Transactional
    fun reopen(id: UUID, reason: String, operationKey: String, payloadHash: String) = decide(id, reason, operationKey, payloadHash, true)
    private fun decide(id: UUID, reason: String, operationKey: String, payloadHash: String, reopen: Boolean): UUID {
        if (reason.isBlank()) throw ConflictException("period decision reason is required")
        val actor = current.current()
        val namespace = if (reopen) "attendance.period.reopen" else "attendance.period.close"
        val prior = outcomes.find(actor.tenantId, namespace, operationKey)
        if (prior != null) { if (prior.payloadHash != payloadHash || prior.payload.targetId != id) throw ConflictException("operation identity conflicts"); return id }
        val entity = periods.findById(id).orElseThrow { ConflictException("attendance period not found") }
        if (entity.tenantId != actor.tenantId) throw ConflictException("attendance period is outside tenant scope")
        if (reopen) {
            if (entity.closedAt == null || entity.reopenedAt != null) throw ConflictException("attendance period is not closed")
            entity.reopenedAt = Instant.now()
        } else {
            if (entity.closedAt != null && entity.reopenedAt == null) throw ConflictException("attendance period is already closed")
            entity.closedAt = Instant.now(); entity.reopenedAt = null
        }
        periods.save(entity); outcomes.record(actor.tenantId, namespace, operationKey, payloadHash, HrisOutcomePayload(id, actor.userId, id, "period", if (reopen) "OPEN" else "CLOSED")); events.append(actor.tenantId, entity.id, if (reopen) "AttendancePeriodReopened" else "AttendancePeriodClosed", 2, Instant.now(), mapOf("reason" to reason))
        return entity.id
    }
}
