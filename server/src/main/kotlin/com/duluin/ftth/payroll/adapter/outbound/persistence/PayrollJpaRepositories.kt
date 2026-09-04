package com.duluin.ftth.payroll.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.time.LocalDate
import java.util.UUID

interface PayrollCompensationJpaRepository : JpaRepository<PayrollCompensationJpaEntity, UUID> {
    fun findByTenantIdAndEmployeeId(tenantId: UUID, employeeId: UUID): List<PayrollCompensationJpaEntity>
}
interface PayrollComponentJpaRepository : JpaRepository<PayrollComponentJpaEntity, UUID> {
    fun findByTenantIdAndEmployeeId(tenantId: UUID, employeeId: UUID): List<PayrollComponentJpaEntity>
}
interface PayrollDeductionRuleJpaRepository : JpaRepository<PayrollDeductionRuleJpaEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<PayrollDeductionRuleJpaEntity>
}
interface PayrollPeriodJpaRepository : JpaRepository<PayrollPeriodJpaEntity, UUID> {
    fun findByTenantIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(tenantId: UUID, from: LocalDate, to: LocalDate): PayrollPeriodJpaEntity?
}
interface PayrollRunJpaRepository : JpaRepository<PayrollRunJpaEntity, UUID> {
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): PayrollRunJpaEntity?
    fun findByTenantIdAndId(tenantId: UUID, id: UUID): PayrollRunJpaEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByTenantIdAndId(tenantId: UUID, id: UUID): PayrollRunJpaEntity?
}
interface PayrollSnapshotJpaRepository : JpaRepository<PayrollSnapshotJpaEntity, UUID> {
    fun findByTenantIdAndRunId(tenantId: UUID, runId: UUID): List<PayrollSnapshotJpaEntity>
}
interface PayrollLineJpaRepository : JpaRepository<PayrollLineJpaEntity, UUID> {
    fun findByTenantIdAndSnapshotId(tenantId: UUID, snapshotId: UUID): List<PayrollLineJpaEntity>
}
interface PayrollApprovalJpaRepository : JpaRepository<PayrollApprovalJpaEntity, UUID> {
    fun findByTenantIdAndRunIdOrderByTierAscDecidedAtAsc(tenantId: UUID, runId: UUID): List<PayrollApprovalJpaEntity>
}
interface PayrollPaymentJpaRepository : JpaRepository<PayrollPaymentJpaEntity, UUID> {
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): PayrollPaymentJpaEntity?
    fun findByTenantIdAndRunId(tenantId: UUID, runId: UUID): PayrollPaymentJpaEntity?
}
interface PayrollVoidJpaRepository : JpaRepository<PayrollVoidJpaEntity, UUID> {
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): PayrollVoidJpaEntity?
}
interface PayrollOutboxJpaRepository : JpaRepository<PayrollOutboxJpaEntity, UUID> {
    fun findFirstByTenantIdAndAggregateIdOrderBySequenceDesc(tenantId: UUID, aggregateId: UUID): PayrollOutboxJpaEntity?
}
interface PayrollAuditJpaRepository : JpaRepository<PayrollAuditJpaEntity, UUID>
interface PayrollOperationOutcomeJpaRepository : JpaRepository<PayrollOperationOutcomeJpaEntity, UUID> {
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, operationKey: String): PayrollOperationOutcomeJpaEntity?
}
interface PayrollApprovalPolicyJpaRepository : JpaRepository<PayrollApprovalPolicyJpaEntity, UUID> {
    fun findFirstByTenantIdOrderByPolicyVersionDesc(tenantId: UUID): PayrollApprovalPolicyJpaEntity?
}
