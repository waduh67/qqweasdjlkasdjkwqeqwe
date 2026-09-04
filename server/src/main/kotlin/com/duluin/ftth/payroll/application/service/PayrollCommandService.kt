package com.duluin.ftth.payroll.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.payroll.PayrollApi
import com.duluin.ftth.payroll.RedactedPayslip
import com.duluin.ftth.payroll.redactedPayslip
import com.duluin.ftth.payroll.domain.*
import com.duluin.ftth.payroll.application.port.PayrollRunStore
import com.duluin.ftth.payroll.application.port.PayrollEventStore
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollPaymentJpaEntity
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollPaymentJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollVoidJpaEntity
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollVoidJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollOperationOutcomeJpaEntity
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollOperationOutcomeJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Service
class PayrollCommandService(
    private val runs: PayrollRunStore,
    private val calculator: PayrollCalculationService,
    private val configuration: PayrollConfigurationService,
    private val current: CurrentUserProvider,
    private val audit: AuditRecorder,
    private val events: PayrollEventStore,
    private val payments: PayrollPaymentJpaRepository,
    private val voids: PayrollVoidJpaRepository,
    private val outcomes: PayrollOperationOutcomeJpaRepository,
    private val mapper: ObjectMapper,
) : PayrollApi {
    @Transactional
    fun draft(periodId: UUID, operationKey: String, payloadHash: String): PayrollRun {
        val actor = current.current()
        val period = configuration.period(actor.tenantId, periodId)
        verifyHash("payroll.run.draft", actor.tenantId, actor.userId, payloadHash, mapOf("periodId" to periodId.toString(), "operationKey" to operationKey))
        requireTenant(actor.tenantId, period.tenantId)
        runs.findByOperation(actor.tenantId, operationKey, payloadHash)?.let { return it }
        val run = PayrollRun.draft(actor.tenantId, actor.userId, period, operationKey, payloadHash, configuration.approvalTiers(actor.tenantId, actor.userId))
        val saved = runs.save(run)
        recordOutcome(actor.tenantId, "payroll.run.draft", operationKey, payloadHash, saved)
        audit.record("payroll.run.drafted", "PayrollRun", saved.id, actor.tenantId)
        events.append(actor.tenantId, saved.id, "PayrollRunDrafted", Instant.now())
        return saved
    }

    @Transactional
    fun calculate(runId: UUID, employeeId: UUID, periodId: UUID, operationKey: String, payloadHash: String): PayrollRun {
        val actor = current.current()
        val run = requireRun(actor.tenantId, runId)
        verifyHash("payroll.run.calculate", actor.tenantId, actor.userId, payloadHash, mapOf("runId" to runId.toString(), "employeeId" to employeeId.toString(), "periodId" to periodId.toString(), "operationKey" to operationKey))
        replay(actor.tenantId, "payroll.run.calculate", operationKey, payloadHash, runId)?.let { return it }
        val period = configuration.period(actor.tenantId, periodId)
        if (period.id != run.period.id) throw ConflictException("calculation period does not match run")
        val input = configuration.input(actor.tenantId, employeeId, period)
        val calculated = run.apply { calculated(calculator.calculate(input)) }
        val saved = runs.save(calculated)
        audit.record("payroll.run.calculated", "PayrollRun", runId, actor.tenantId, mapOf("snapshotId" to saved.snapshot?.id))
        events.append(actor.tenantId, runId, "PayrollRunCalculated", Instant.now(), mapOf("snapshotId" to saved.snapshot?.id))
        recordOutcome(actor.tenantId, "payroll.run.calculate", operationKey, payloadHash, saved)
        return saved
    }

    @Transactional
    fun review(runId: UUID, operationKey: String, payloadHash: String): PayrollRun = transition(runId, "payroll.run.reviewed", operationKey, payloadHash) { it.review(current.current().userId, Instant.now()) }

    @Transactional
    fun approve(runId: UUID, operationKey: String, payloadHash: String, reason: String?): PayrollRun = transition(runId, "payroll.run.approved", operationKey, payloadHash, mapOf("reason" to (reason ?: ""))) { it.approve(current.current().userId, Instant.now(), reason) }

    @Transactional
    fun pay(runId: UUID, operationKey: String, payloadHash: String): PayrollRun {
        val actor = current.current()
        require(operationKey.isNotBlank()) { "payment operation key is required" }
        verifyHash("payroll.run.pay", actor.tenantId, actor.userId, payloadHash, mapOf("runId" to runId.toString(), "operationKey" to operationKey))
        replay(actor.tenantId, "payroll.run.pay", operationKey, payloadHash, runId)?.let { return it }
        payments.findByTenantIdAndOperationKey(actor.tenantId, operationKey)?.let { payment ->
            if (payment.runId != runId) throw ConflictException("payment operation belongs to another run")
            return requireRun(actor.tenantId, runId)
        }
        val run = requireRun(actor.tenantId, runId)
        if (payments.findByTenantIdAndRunId(actor.tenantId, runId) != null) throw ConflictException("payroll run is already paid")
        run.pay(Instant.now())
        val snapshot = run.snapshot ?: throw ConflictException("calculation snapshot is required")
        payments.save(PayrollPaymentJpaEntity().apply { this.runId = runId; this.operationKey = operationKey; amountMinor = snapshot.net.minor.value; currency = snapshot.net.currency; paidAt = Instant.now() })
        return saveTransition(run, actor.tenantId, "payroll.run.paid").also { recordOutcome(actor.tenantId, "payroll.run.pay", operationKey, payloadHash, it) }
    }

    @Transactional
    fun void(runId: UUID, operationKey: String, payloadHash: String, reason: String): PayrollRun {
        val actor = current.current()
        require(operationKey.isNotBlank()) { "void operation key is required" }
        verifyHash("payroll.run.void", actor.tenantId, actor.userId, payloadHash, mapOf("runId" to runId.toString(), "operationKey" to operationKey, "reason" to reason))
        replay(actor.tenantId, "payroll.run.void", operationKey, payloadHash, runId)?.let { return it }
        voids.findByTenantIdAndOperationKey(actor.tenantId, operationKey)?.let { value ->
            if (value.runId != runId) throw ConflictException("void operation belongs to another run")
            return requireRun(actor.tenantId, runId)
        }
        val run = requireRun(actor.tenantId, runId)
        run.void(actor.userId, Instant.now(), reason)
        voids.save(PayrollVoidJpaEntity().apply { this.runId = runId; this.operationKey = operationKey; reversalOf = runId; actorId = actor.userId; this.reason = reason; voidedAt = Instant.now() })
        return saveTransition(run, actor.tenantId, "payroll.run.voided").also { recordOutcome(actor.tenantId, "payroll.run.void", operationKey, payloadHash, it) }
    }

    override fun payslip(tenantId: UUID, runId: UUID, employeeId: UUID): RedactedPayslip {
        val actor = current.current()
        requireTenant(actor.tenantId, tenantId)
        if (actor.userId != employeeId && !actor.hasPermission("payroll.payslip.view")) throw AccessDeniedException("peer payslip access is forbidden")
        val snapshot = requireRun(tenantId, runId).snapshot ?: throw ConflictException("payslip is not calculated")
        if (snapshot.employeeId != employeeId) throw AccessDeniedException("payslip employee mismatch")
        return snapshot.redactedPayslip()
    }

    private fun transition(runId: UUID, action: String, operationKey: String, payloadHash: String, fields: Map<String, String> = emptyMap(), change: (PayrollRun) -> Unit): PayrollRun {
        val actor = current.current()
        val run = requireRun(actor.tenantId, runId)
        verifyHash(action, actor.tenantId, actor.userId, payloadHash, mapOf("runId" to runId.toString(), "operationKey" to operationKey) + fields)
        replay(actor.tenantId, action, operationKey, payloadHash, runId)?.let { return it }
        change(run)
        return saveTransition(run, actor.tenantId, action).also { recordOutcome(actor.tenantId, action, operationKey, payloadHash, it) }
    }

    private fun saveTransition(run: PayrollRun, tenantId: UUID, action: String): PayrollRun = runs.save(run).also { saved ->
        audit.record(action, "PayrollRun", run.id, tenantId)
        events.append(tenantId, run.id, action.removePrefix("payroll.run").removePrefix(".").replaceFirstChar { it.uppercase() }, Instant.now(), mapOf("state" to saved.state.name))
    }

    private fun requireRun(tenantId: UUID, runId: UUID): PayrollRun = runs.find(tenantId, runId) ?: throw ConflictException("payroll run not found")
    private fun requireTenant(currentTenant: UUID, requestedTenant: UUID) { if (currentTenant != requestedTenant) throw AccessDeniedException("tenant scope mismatch") }
    private fun verifyHash(namespace: String, tenantId: UUID, actorId: UUID, supplied: String, fields: Map<String, String>) {
        require(supplied == PayrollCanonical.hash(namespace, tenantId, actorId, fields)) { "payload hash does not match canonical command" }
    }
    private fun replay(tenantId: UUID, namespace: String, operationKey: String, payloadHash: String, runId: UUID): PayrollRun? {
        require(operationKey.isNotBlank() && payloadHash.isNotBlank()) { "operation identity is required" }
        val outcome = outcomes.findByTenantIdAndNamespaceAndOperationKey(tenantId, namespace, operationKey) ?: return null
        if (outcome.payloadHash != payloadHash) throw ConflictException("operation key was used with another payload")
        val snapshot = decodeRunOutcome(outcome.outcome)
        if (snapshot.tenantId != tenantId || snapshot.id != runId) throw ConflictException("operation outcome target mismatch")
        return PayrollRun.rehydrate(snapshot.id, snapshot.tenantId, snapshot.requesterId, snapshot.period, snapshot.operationKey, snapshot.payloadHash, snapshot.state, snapshot.snapshot, snapshot.tiers, snapshot.approvals)
    }
    private fun decodeRunOutcome(raw: String): PayrollRunOutcomeSnapshot {
        try {
            val tree = mapper.readTree(raw)
            if (tree.has("runId") && tree.has("state") && !tree.has("requesterId")) throw ConflictException("legacy payroll outcome requires reconciliation")
            if (!tree.has("requesterId") || !tree.has("period") || !tree.has("operationKey")) throw ConflictException("unsupported payroll outcome requires reconciliation")
            return mapper.treeToValue(tree, PayrollRunOutcomeSnapshot::class.java)
        } catch (exception: ConflictException) {
            throw exception
        } catch (_: Exception) {
            throw ConflictException("malformed payroll outcome requires reconciliation")
        }
    }
    private fun recordOutcome(tenantId: UUID, namespace: String, operationKey: String, payloadHash: String, run: PayrollRun) {
        outcomes.save(PayrollOperationOutcomeJpaEntity().apply { this.namespace = namespace; this.operationKey = operationKey; this.payloadHash = payloadHash; outcome = mapper.writeValueAsString(run.outcomeSnapshot()) })
    }
}
