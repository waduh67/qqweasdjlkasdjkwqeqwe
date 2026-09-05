package com.duluin.ftth.payroll.adapter.outbound.persistence

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.payroll.application.port.PayrollEventStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
class PayrollEventPersistenceAdapter(
    private val outbox: PayrollOutboxJpaRepository,
    private val audit: PayrollAuditJpaRepository,
    private val mapper: ObjectMapper,
    private val current: CurrentUserProvider,
) : PayrollEventStore {
    @Transactional
    override fun append(tenantId: UUID, aggregateId: UUID, type: String, at: Instant, detail: Map<String, Any?>) {
        val safe = detail.filterKeys { it in setOf("state", "tier", "reason", "snapshotId") }
        val payload = mapper.writeValueAsString(safe)
        val sequence = (outbox.findFirstByTenantIdAndAggregateIdOrderBySequenceDesc(tenantId, aggregateId)?.sequence ?: 0) + 1
        outbox.save(PayrollOutboxJpaEntity().apply { this.aggregateId = aggregateId; eventType = type; this.sequence = sequence; this.payload = payload })
        audit.save(PayrollAuditJpaEntity().apply { actorId = current.currentOrNull()?.userId; action = type; entityId = aggregateId; this.detail = payload; occurredAt = at })
    }
}
