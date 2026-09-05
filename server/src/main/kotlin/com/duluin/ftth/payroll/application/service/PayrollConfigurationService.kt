package com.duluin.ftth.payroll.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollCompensationJpaEntity
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollCompensationJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollPeriodJpaEntity
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollPeriodJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollComponentJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollDeductionRuleJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollApprovalPolicyJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollApprovalPolicyJpaEntity
import com.duluin.ftth.payroll.domain.EffectiveCompensation
import com.duluin.ftth.payroll.domain.PayCalendar
import com.duluin.ftth.payroll.domain.PayrollInput
import com.duluin.ftth.payroll.domain.PayComponent
import com.duluin.ftth.payroll.domain.ComponentKind
import com.duluin.ftth.payroll.domain.DeductionRule
import com.duluin.ftth.payroll.domain.DeductionKind
import com.duluin.ftth.payroll.domain.Money
import com.duluin.ftth.payroll.domain.MinorUnit
import com.duluin.ftth.payroll.domain.ApprovalTier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollOperationOutcomeJpaRepository
import com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollOperationOutcomeJpaEntity
import com.duluin.ftth.payroll.domain.PayrollCanonical
import java.time.Instant
import java.util.UUID

@Service
class PayrollConfigurationService(
    private val compensation: PayrollCompensationJpaRepository,
    private val periods: PayrollPeriodJpaRepository,
    private val components: PayrollComponentJpaRepository,
    private val deductions: PayrollDeductionRuleJpaRepository,
    private val policies: PayrollApprovalPolicyJpaRepository,
    private val mapper: ObjectMapper,
    private val outcomes: PayrollOperationOutcomeJpaRepository,
    private val current: CurrentUserProvider,
    private val audit: AuditRecorder,
) {
    @Transactional(readOnly = true)
    fun approvalTiers(tenantId: java.util.UUID, requesterId: java.util.UUID): List<ApprovalTier> {
        val policy = policies.findFirstByTenantIdOrderByPolicyVersionDesc(tenantId) ?: throw IllegalArgumentException("payroll approval policy is not configured")
        val approvers = mapper.readValue(policy.approverIds, Array<java.util.UUID>::class.java).toSet() - requesterId
        if (approvers.isEmpty()) throw IllegalArgumentException("payroll approval policy has no independent approver")
        return listOf(ApprovalTier(1, approvers))
    }

    @Transactional
    fun saveApprovalPolicy(tenantId: java.util.UUID, approverIds: Set<java.util.UUID>, operationKey: String, payloadHash: String): Long {
        require(approverIds.isNotEmpty()) { "at least one approver is required" }
        val actor = current.current()
        val namespace = "payroll.approval-policy.save"
        val sorted = approverIds.map(UUID::toString).sorted().joinToString(",")
        verify(namespace, tenantId, actor.userId, payloadHash, mapOf("approvers" to sorted, "intent" to "replace-policy", "operationKey" to operationKey))
        outcomes.findByTenantIdAndNamespaceAndOperationKey(tenantId, namespace, operationKey)?.let { existing ->
            if (existing.payloadHash != payloadHash) throw IllegalArgumentException("operation payload conflict")
            return mapper.readValue(existing.outcome, Map::class.java)["version"].toString().toLong()
        }
        val next = (policies.findFirstByTenantIdOrderByPolicyVersionDesc(tenantId)?.policyVersion ?: 0) + 1
        policies.save(PayrollApprovalPolicyJpaEntity().apply { policyVersion = next; this.approverIds = mapper.writeValueAsString(approverIds.sorted()) })
        outcomes.save(PayrollOperationOutcomeJpaEntity().apply { this.namespace = "payroll.approval-policy.save"; this.operationKey = operationKey; this.payloadHash = payloadHash; this.outcome = mapper.writeValueAsString(mapOf("version" to next)) })
        return next
    }
    @Transactional(readOnly = true)
    fun period(tenantId: java.util.UUID, periodId: java.util.UUID): PayCalendar {
        val entity = periods.findById(periodId).orElseThrow { IllegalArgumentException("pay period not found") }
        require(entity.tenantId == tenantId) { "tenant scope mismatch" }
        return PayCalendar(entity.id, tenantId, entity.validFrom, entity.validTo, entity.payDate, entity.closedAt)
    }

    @Transactional(readOnly = true)
    fun input(tenantId: java.util.UUID, employeeId: java.util.UUID, period: PayCalendar): PayrollInput {
        val compensation = compensation.findByTenantIdAndEmployeeId(tenantId, employeeId).firstOrNull { it.validFrom <= period.from && (it.validTo == null || !it.validTo!!.isBefore(period.from)) }
            ?: throw IllegalArgumentException("effective compensation not found")
        val componentValues = components.findByTenantIdAndEmployeeId(tenantId, employeeId).filter { it.validFrom <= period.to && (it.validTo == null || !it.validTo!!.isBefore(period.from)) }
            .map { PayComponent(it.code, ComponentKind.valueOf(it.kind), Money(it.currency, MinorUnit(it.amountMinor)), it.validFrom, it.validTo) }
        val rules = deductions.findByTenantId(tenantId)
        fun rule(entity: com.duluin.ftth.payroll.adapter.outbound.persistence.PayrollDeductionRuleJpaEntity) = DeductionRule(entity.code, DeductionKind.valueOf(entity.kind), entity.rate, entity.fixedMinor?.let { Money(entity.currency ?: "IDR", MinorUnit(it)) })
        val tax = rules.firstOrNull { it.kind == "TAX" }?.let(::rule) ?: throw IllegalArgumentException("tax rule not configured")
        return PayrollInput(tenantId, employeeId, period, EffectiveCompensation(compensation.id, tenantId, employeeId, compensation.validFrom, compensation.validTo, Money(compensation.currency, MinorUnit(compensation.monthlyBaseMinor)), Money(compensation.currency, MinorUnit(compensation.hourlyRateMinor))), componentValues, 0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, rules.filter { it.kind != "TAX" }.map(::rule), tax)
    }
    @Transactional
    fun saveCompensation(value: EffectiveCompensation, operationKey: String, payloadHash: String): EffectiveCompensation {
        val actor = current.current()
        require(actor.tenantId == value.tenantId) { "tenant scope mismatch" }
        val namespace = "payroll.compensation.save"
        verify(namespace, actor.tenantId, actor.userId, payloadHash, mapOf("employeeId" to value.employeeId.toString(), "validFrom" to value.validFrom.toString(), "validTo" to (value.validTo?.toString() ?: ""), "currency" to value.monthlyBase.currency, "monthlyBaseMinor" to value.monthlyBase.minor.value.toString(), "hourlyRateMinor" to value.hourlyRate.minor.value.toString(), "operationKey" to operationKey))
        outcomes.findByTenantIdAndNamespaceAndOperationKey(actor.tenantId, namespace, operationKey)?.let { existing ->
            if (existing.payloadHash != payloadHash) throw IllegalArgumentException("operation payload conflict")
            val persisted = compensation.findById(value.id).orElseThrow { IllegalArgumentException("compensation outcome missing") }
            return EffectiveCompensation(persisted.id, actor.tenantId, persisted.employeeId!!, persisted.validFrom, persisted.validTo, Money(persisted.currency, MinorUnit(persisted.monthlyBaseMinor)), Money(persisted.currency, MinorUnit(persisted.hourlyRateMinor)))
        }
        val entity = PayrollCompensationJpaEntity(value.id).apply {
            employeeId = value.employeeId
            validFrom = value.validFrom
            validTo = value.validTo
            currency = value.monthlyBase.currency
            monthlyBaseMinor = value.monthlyBase.minor.value
            hourlyRateMinor = value.hourlyRate.minor.value
        }
        compensation.save(entity)
        outcomes.save(PayrollOperationOutcomeJpaEntity().apply { this.namespace = "payroll.compensation.save"; this.operationKey = operationKey; this.payloadHash = payloadHash; this.outcome = mapper.writeValueAsString(mapOf("compensationId" to value.id)) })
        audit.record("payroll.compensation.created", "PayrollCompensation", value.id, value.tenantId)
        return value
    }

    @Transactional
    fun createPeriod(period: PayCalendar, operationKey: String, payloadHash: String): PayCalendar {
        val actor = current.current()
        require(actor.tenantId == period.tenantId) { "tenant scope mismatch" }
        verify("payroll.period.create", actor.tenantId, actor.userId, payloadHash, mapOf("periodId" to period.id.toString(), "from" to period.from.toString(), "to" to period.to.toString(), "payDate" to period.payDate.toString(), "operationKey" to operationKey))
        outcomes.findByTenantIdAndNamespaceAndOperationKey(actor.tenantId, "payroll.period.create", operationKey)?.let { existing -> if (existing.payloadHash != payloadHash) throw IllegalArgumentException("operation payload conflict"); return decodePeriodOutcome(existing.outcome, actor.tenantId) }
        periods.save(PayrollPeriodJpaEntity(period.id).apply { validFrom = period.from; validTo = period.to; payDate = period.payDate; closedAt = period.closedAt })
        outcomes.save(PayrollOperationOutcomeJpaEntity().apply { namespace = "payroll.period.create"; this.operationKey = operationKey; this.payloadHash = payloadHash; outcome = mapper.writeValueAsString(period) })
        audit.record("payroll.period.created", "PayrollPeriod", period.id, period.tenantId)
        return period
    }

    @Transactional
    fun closePeriod(periodId: java.util.UUID, operationKey: String, payloadHash: String): PayCalendar {
        val actor = current.current()
        verify("payroll.period.close", actor.tenantId, actor.userId, payloadHash, mapOf("periodId" to periodId.toString(), "operationKey" to operationKey))
        outcomes.findByTenantIdAndNamespaceAndOperationKey(actor.tenantId, "payroll.period.close", operationKey)?.let { existing -> if (existing.payloadHash != payloadHash) throw IllegalArgumentException("operation payload conflict"); return decodePeriodOutcome(existing.outcome, actor.tenantId) }
        val entity = periods.findById(periodId).orElseThrow { IllegalArgumentException("pay period not found") }
        entity.closedAt = Instant.now()
        periods.save(entity)
        val response = PayCalendar(entity.id, actor.tenantId, entity.validFrom, entity.validTo, entity.payDate, entity.closedAt)
        outcomes.save(PayrollOperationOutcomeJpaEntity().apply { namespace = "payroll.period.close"; this.operationKey = operationKey; this.payloadHash = payloadHash; outcome = mapper.writeValueAsString(response) })
        audit.record("payroll.period.closed", "PayrollPeriod", periodId, actor.tenantId)
        return response
    }
    private fun verify(namespace: String, tenantId: java.util.UUID, actorId: java.util.UUID, supplied: String, fields: Map<String, String>) {
        require(supplied == PayrollCanonical.hash(namespace, tenantId, actorId, fields)) { "payload hash does not match canonical command" }
    }
    private fun decodePeriodOutcome(raw: String, tenantId: java.util.UUID): PayCalendar {
        try {
            val tree = mapper.readTree(raw)
            if (!tree.has("id") || !tree.has("tenantId") || !tree.has("from") || !tree.has("to") || !tree.has("payDate")) throw ConflictException("legacy payroll period outcome requires reconciliation")
            val period = mapper.treeToValue(tree, PayCalendar::class.java)
            if (period.tenantId != tenantId) throw ConflictException("operation outcome tenant mismatch")
            return period
        } catch (exception: ConflictException) {
            throw exception
        } catch (_: Exception) {
            throw ConflictException("malformed payroll period outcome requires reconciliation")
        }
    }
}
