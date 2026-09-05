package com.duluin.ftth.payroll.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity @Table(name = "payroll_compensation")
class PayrollCompensationJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "employee_id", nullable = false, updatable = false) var employeeId: UUID? = null
    @Column(name = "valid_from", nullable = false, updatable = false) var validFrom: LocalDate = LocalDate.MIN
    @Column(name = "valid_to", updatable = false) var validTo: LocalDate? = null
    @Column(nullable = false, updatable = false) var currency: String = "IDR"
    @Column(name = "monthly_base_minor", nullable = false, updatable = false) var monthlyBaseMinor: Long = 0
    @Column(name = "hourly_rate_minor", nullable = false, updatable = false) var hourlyRateMinor: Long = 0
}

@Entity @Table(name = "payroll_component")
class PayrollComponentJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "employee_id", nullable = false, updatable = false) var employeeId: UUID? = null
    @Column(nullable = false, updatable = false) var code: String = ""
    @Column(nullable = false, updatable = false) var kind: String = ""
    @Column(name = "amount_minor", nullable = false, updatable = false) var amountMinor: Long = 0
    @Column(nullable = false, updatable = false) var currency: String = "IDR"
    @Column(name = "valid_from", nullable = false, updatable = false) var validFrom: LocalDate = LocalDate.MIN
    @Column(name = "valid_to", updatable = false) var validTo: LocalDate? = null
}

@Entity @Table(name = "payroll_deduction_rule")
class PayrollDeductionRuleJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(nullable = false, updatable = false) var code: String = ""
    @Column(nullable = false, updatable = false) var kind: String = "TAX"
    @Column(updatable = false) var rate: BigDecimal? = null
    @Column(name = "fixed_minor", updatable = false) var fixedMinor: Long? = null
    @Column(updatable = false) var currency: String? = null
}

@Entity @Table(name = "payroll_period")
class PayrollPeriodJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "valid_from", nullable = false, updatable = false) var validFrom: LocalDate = LocalDate.MIN
    @Column(name = "valid_to", nullable = false, updatable = false) var validTo: LocalDate = LocalDate.MIN
    @Column(name = "pay_date", nullable = false, updatable = false) var payDate: LocalDate = LocalDate.MIN
    @Column(name = "closed_at") var closedAt: Instant? = null
}

@Entity @Table(name = "payroll_run")
class PayrollRunJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "requester_id", nullable = false, updatable = false) var requesterId: UUID? = null
    @Column(name = "period_id", nullable = false, updatable = false) var periodId: UUID? = null
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String = ""
    @Column(name = "payload_hash", nullable = false, updatable = false) var payloadHash: String = ""
    @Column(nullable = false) var state: String = "DRAFT"
    @Column(name = "approval_tiers", nullable = false, columnDefinition = "jsonb", updatable = false) var approvalTiers: String = "[]"
    @Column(nullable = false) var revision: Long = 0
}

@Entity @Table(name = "payroll_calculation_snapshot")
class PayrollSnapshotJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "run_id", nullable = false, updatable = false) var runId: UUID? = null
    @Column(name = "employee_id", nullable = false, updatable = false) var employeeId: UUID? = null
    @Column(name = "compensation_id", nullable = false, updatable = false) var compensationId: UUID? = null
    @Column(name = "period_from", nullable = false, updatable = false) var periodFrom: LocalDate = LocalDate.MIN
    @Column(name = "period_to", nullable = false, updatable = false) var periodTo: LocalDate = LocalDate.MIN
    @Column(name = "hris_session_ids", nullable = false, columnDefinition = "jsonb", updatable = false) var hrisSessionIds: String = "[]"
    @Column(name = "gross_minor", nullable = false, updatable = false) var grossMinor: Long = 0
    @Column(name = "deduction_minor", nullable = false, updatable = false) var deductionMinor: Long = 0
    @Column(name = "tax_minor", nullable = false, updatable = false) var taxMinor: Long = 0
    @Column(name = "net_minor", nullable = false, updatable = false) var netMinor: Long = 0
    @Column(nullable = false, updatable = false) var currency: String = "IDR"
    @Column(name = "calculated_at", nullable = false, updatable = false) var calculatedAt: Instant = Instant.EPOCH
}

@Entity @Table(name = "payroll_calculation_line")
class PayrollLineJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "snapshot_id", nullable = false, updatable = false) var snapshotId: UUID? = null
    @Column(nullable = false, updatable = false) var code: String = ""
    @Column(nullable = false, updatable = false) var kind: String = ""
    @Column(name = "amount_minor", nullable = false, updatable = false) var amountMinor: Long = 0
    @Column(nullable = false, updatable = false) var currency: String = "IDR"
}

@Entity @Table(name = "payroll_approval")
class PayrollApprovalJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "run_id", nullable = false, updatable = false) var runId: UUID? = null
    @Column(nullable = false, updatable = false) var tier: Int = 1
    @Column(name = "approver_id", nullable = false, updatable = false) var approverId: UUID? = null
    @Column(nullable = false, updatable = false) var decision: String = "APPROVE"
    @Column(updatable = false) var reason: String? = null
    @Column(name = "decided_at", nullable = false, updatable = false) var decidedAt: Instant = Instant.EPOCH
}

@Entity @Table(name = "payroll_payment")
class PayrollPaymentJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "run_id", nullable = false, updatable = false) var runId: UUID? = null
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String = ""
    @Column(name = "amount_minor", nullable = false, updatable = false) var amountMinor: Long = 0
    @Column(nullable = false, updatable = false) var currency: String = "IDR"
    @Column(name = "paid_at", nullable = false, updatable = false) var paidAt: Instant = Instant.EPOCH
}

@Entity @Table(name = "payroll_void")
class PayrollVoidJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "run_id", nullable = false, updatable = false) var runId: UUID? = null
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String = ""
    @Column(name = "reversal_of", nullable = false, updatable = false) var reversalOf: UUID? = null
    @Column(name = "actor_id", nullable = false, updatable = false) var actorId: UUID? = null
    @Column(nullable = false, updatable = false) var reason: String = ""
    @Column(name = "voided_at", nullable = false, updatable = false) var voidedAt: Instant = Instant.EPOCH
}

@Entity @Table(name = "payroll_outbox")
class PayrollOutboxJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "aggregate_id", nullable = false, updatable = false) var aggregateId: UUID? = null
    @Column(name = "event_type", nullable = false, updatable = false) var eventType: String = ""
    @Column(nullable = false, updatable = false) var sequence: Long = 0
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false) var payload: String = "{}"
    @Column(name = "published_at") var publishedAt: Instant? = null
}

@Entity @Table(name = "payroll_audit")
class PayrollAuditJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "actor_id") var actorId: UUID? = null
    @Column(nullable = false, updatable = false) var action: String = ""
    @Column(name = "entity_type", nullable = false, updatable = false) var entityType: String = "PayrollRun"
    @Column(name = "entity_id", updatable = false) var entityId: UUID? = null
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false) var detail: String = "{}"
    @Column(name = "occurred_at", nullable = false, updatable = false) var occurredAt: Instant = Instant.EPOCH
}

@Entity @Table(name = "payroll_operation_outcome")
class PayrollOperationOutcomeJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(nullable = false, updatable = false) var namespace: String = ""
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String = ""
    @Column(name = "payload_hash", nullable = false, updatable = false) var payloadHash: String = ""
    @Column(nullable = false, updatable = false) var status: String = "COMPLETED"
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false) var outcome: String = "{}"
}

@Entity @Table(name = "payroll_approval_policy")
class PayrollApprovalPolicyJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "policy_version", nullable = false, updatable = false) var policyVersion: Long = 1
    @Column(name = "approver_ids", nullable = false, columnDefinition = "jsonb", updatable = false) var approverIds: String = "[]"
}
