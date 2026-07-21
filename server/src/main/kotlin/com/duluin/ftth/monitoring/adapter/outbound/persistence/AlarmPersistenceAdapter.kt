package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRuleRepository
import com.duluin.ftth.monitoring.domain.model.Alarm
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmRule
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AlarmPersistenceAdapter(
    private val jpa: AlarmJpaRepository,
) : AlarmRepository {

    override fun save(alarm: Alarm): Alarm {
        val entity = jpa.findById(alarm.id).orElse(null)?.apply {
            entityLabel = alarm.entityLabel
            severity = alarm.severity
            status = alarm.status
            message = alarm.message
            measuredValue = alarm.measuredValue
            lastSeenAt = alarm.lastSeenAt
            clearedAt = alarm.clearedAt
            acknowledgedAt = alarm.acknowledgedAt
            acknowledgedBy = alarm.acknowledgedBy
            occurrenceCount = alarm.occurrenceCount
        } ?: AlarmJpaEntity(
            id = alarm.id,
            kind = alarm.kind,
            entityType = alarm.entityType,
            entityId = alarm.entityId,
            entityLabel = alarm.entityLabel,
            severity = alarm.severity,
            status = alarm.status,
            message = alarm.message,
            measuredValue = alarm.measuredValue,
            raisedAt = alarm.raisedAt,
            lastSeenAt = alarm.lastSeenAt,
            clearedAt = alarm.clearedAt,
            acknowledgedAt = alarm.acknowledgedAt,
            acknowledgedBy = alarm.acknowledgedBy,
            occurrenceCount = alarm.occurrenceCount,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Alarm? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findOpen(kind: AlarmKind, entityId: UUID): Alarm? =
        jpa.findByKindAndEntityIdAndStatusNot(kind, entityId, AlarmStatus.CLEARED)?.toDomain()

    override fun findAllOpenByKind(kind: AlarmKind): List<Alarm> =
        jpa.findByKindAndStatusNot(kind, AlarmStatus.CLEARED).map { it.toDomain() }

    override fun findAllOpen(): List<Alarm> =
        jpa.findByStatusNot(AlarmStatus.CLEARED).map { it.toDomain() }

    override fun search(status: AlarmStatus?, kind: AlarmKind?, pageRequest: PageRequest): Page<Alarm> {
        val spec = equals<AlarmJpaEntity>("status", status).and(equals("kind", kind))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun countByStatus(): Map<AlarmStatus, Long> =
        jpa.countGroupedByStatus().associate { it.status to it.total }

    private fun <T : Any> equals(attribute: String, value: Any?): Specification<T> =
        Specification { root, _, cb ->
            if (value == null) cb.conjunction() else cb.equal(root.get<Any>(attribute), value)
        }
}

@Component
class AlarmRulePersistenceAdapter(
    private val jpa: AlarmRuleJpaRepository,
) : AlarmRuleRepository {

    override fun save(rule: AlarmRule): AlarmRule {
        val entity = jpa.findById(rule.id).orElse(null)?.apply {
            enabled = rule.enabled
            warningThreshold = rule.warningThreshold
            criticalThreshold = rule.criticalThreshold
            sustainSeconds = rule.sustainSeconds
        } ?: AlarmRuleJpaEntity(
            id = rule.id,
            kind = rule.kind,
            enabled = rule.enabled,
            warningThreshold = rule.warningThreshold,
            criticalThreshold = rule.criticalThreshold,
            sustainSeconds = rule.sustainSeconds,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByKind(kind: AlarmKind): AlarmRule? = jpa.findByKind(kind)?.toDomain()

    override fun findAll(): List<AlarmRule> = jpa.findAll().map { it.toDomain() }
}

private fun AlarmJpaEntity.toDomain(): Alarm = Alarm.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    kind = kind,
    entityType = entityType,
    entityId = entityId,
    entityLabel = entityLabel,
    severity = severity,
    status = status,
    message = message,
    measuredValue = measuredValue,
    raisedAt = raisedAt,
    lastSeenAt = lastSeenAt,
    clearedAt = clearedAt,
    acknowledgedAt = acknowledgedAt,
    acknowledgedBy = acknowledgedBy,
    occurrenceCount = occurrenceCount,
)

private fun AlarmRuleJpaEntity.toDomain(): AlarmRule = AlarmRule.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    kind = kind,
    enabled = enabled,
    warningThreshold = warningThreshold,
    criticalThreshold = criticalThreshold,
    sustainSeconds = sustainSeconds,
)
