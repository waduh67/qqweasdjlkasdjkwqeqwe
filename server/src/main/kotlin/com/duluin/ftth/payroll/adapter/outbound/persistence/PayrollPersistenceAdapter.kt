package com.duluin.ftth.payroll.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.payroll.application.port.PayrollRunStore
import com.duluin.ftth.payroll.domain.*
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
class PayrollPersistenceAdapter(
    private val runs: PayrollRunJpaRepository,
    private val periods: PayrollPeriodJpaRepository,
    private val snapshots: PayrollSnapshotJpaRepository,
    private val lines: PayrollLineJpaRepository,
    private val approvals: PayrollApprovalJpaRepository,
    private val mapper: ObjectMapper,
) : PayrollRunStore {
    @Transactional(readOnly = true)
    override fun findByOperation(tenantId: UUID, operationKey: String, payloadHash: String): PayrollRun? {
        val entity = runs.findByTenantIdAndOperationKey(tenantId, operationKey) ?: return null
        if (entity.payloadHash != payloadHash) throw ConflictException("operation key was used with another payload")
        return entity.toDomain(tenantId)
    }

    @Transactional(readOnly = true)
    override fun find(tenantId: UUID, runId: UUID): PayrollRun? = runs.findLockedByTenantIdAndId(tenantId, runId)?.toDomain(tenantId)

    @Transactional
    override fun save(run: PayrollRun): PayrollRun {
        val entity = runs.findById(run.id).orElseGet { PayrollRunJpaEntity(run.id) }
        entity.periodId = run.period.id
        entity.requesterId = run.requesterId
        entity.operationKey = run.operationKey
        entity.payloadHash = run.payloadHash
        entity.state = run.state.name
        entity.approvalTiers = mapper.writeValueAsString(run.tiers)
        val saved = runs.save(entity)
        run.snapshot?.let { snapshot ->
            if (snapshots.findByTenantIdAndRunId(run.tenantId, run.id).isEmpty()) {
                val snapshotEntity = snapshot.toEntity(run.tenantId, run.id, mapper)
                snapshots.save(snapshotEntity)
                lines.saveAll(snapshot.lines.map { it.toEntity(run.tenantId, snapshot.id) })
            }
        }
        approvals.saveAll(run.approvals.map { it.toEntity(run.tenantId, run.id) }.filter { approval -> !approvals.existsById(approval.id) })
        return saved.toDomain(run.tenantId)
    }

    private fun PayrollRunJpaEntity.toDomain(tenantId: UUID): PayrollRun {
        val period = periods.findById(periodId ?: throw ConflictException("pay period is missing")).orElseThrow { ConflictException("pay period is missing") }
        val snapshot = snapshots.findByTenantIdAndRunId(tenantId, id).firstOrNull()?.toDomain(tenantId, mapper, lines)
        val decisions = approvals.findByTenantIdAndRunIdOrderByTierAscDecidedAtAsc(tenantId, id).map { ApprovalSnapshot(it.tier, it.approverId!!, ApprovalDecision.valueOf(it.decision), it.decidedAt, it.reason) }
        val tiers = mapper.readValue(approvalTiers, Array<ApprovalTier>::class.java).toList()
        return PayrollRun.rehydrate(id, tenantId, requesterId ?: UUID(0, 0), PayCalendar(period.id, tenantId, period.validFrom, period.validTo, period.payDate, period.closedAt), operationKey, payloadHash, PayrollRunState.valueOf(state), snapshot, tiers, decisions)
    }
}

private fun CalculationSnapshot.toEntity(tenantId: UUID, runId: UUID, mapper: ObjectMapper) = PayrollSnapshotJpaEntity(id).apply {
    this.runId = runId
    employeeId = this@toEntity.employeeId
    compensationId = this@toEntity.compensationId
    periodFrom = this@toEntity.periodFrom
    periodTo = this@toEntity.periodTo
    hrisSessionIds = mapper.writeValueAsString(this@toEntity.hrisSessionIds)
    grossMinor = this@toEntity.gross.minor.value
    deductionMinor = this@toEntity.deductions.minor.value
    taxMinor = this@toEntity.tax.minor.value
    netMinor = this@toEntity.net.minor.value
    currency = this@toEntity.gross.currency
    calculatedAt = this@toEntity.calculatedAt
}

private fun PayrollSnapshotJpaEntity.toDomain(tenantId: UUID, mapper: ObjectMapper, lineRepository: PayrollLineJpaRepository): CalculationSnapshot {
    val money = { value: Long -> Money(currency, MinorUnit(value)) }
    return CalculationSnapshot(id, tenantId, employeeId!!, periodFrom, periodTo, compensationId!!, mapper.readValue(hrisSessionIds, Array<UUID>::class.java).toList(), lineRepository.findByTenantIdAndSnapshotId(tenantId, id).map { PayrollLine(it.code, it.kind, money(it.amountMinor)) }, money(grossMinor), money(deductionMinor), money(taxMinor), money(netMinor), calculatedAt)
}

private fun PayrollLine.toEntity(tenantId: UUID, snapshotId: UUID) = PayrollLineJpaEntity(id = UUID.randomUUID()).apply {
    this.snapshotId = snapshotId
    code = this@toEntity.code
    kind = this@toEntity.kind
    amountMinor = this@toEntity.amount.minor.value
    currency = this@toEntity.amount.currency
}

private fun ApprovalSnapshot.toEntity(tenantId: UUID, runId: UUID) = PayrollApprovalJpaEntity(UUID.randomUUID()).apply {
    this.runId = runId
    tier = this@toEntity.tier
    approverId = this@toEntity.approverId
    decision = this@toEntity.decision.name
    reason = this@toEntity.reason
    decidedAt = this@toEntity.decidedAt
}
