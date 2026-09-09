package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

enum class PolicyOperation {
    RECEIPT, ISSUE, ISSUE_EXCEPTION, OPENING_BALANCE, ADJUSTMENT, LOSS, SCRAP, COUNT_VARIANCE, TITLE_REACQUISITION;
    val exception: Boolean get() = this !in setOf(RECEIPT, ISSUE)
}

data class PolicyTierInput(val minimumMinor: String, val userIds: List<UUID>, val roleIds: List<UUID>)
data class PolicyRuleInput(val operation: PolicyOperation, val tiers: List<PolicyTierInput>)
data class WarehousePolicyInput(val expectedRevision: Long, val currency: String, val expiryHours: Int,
    val warehouseIds: List<UUID>, val rules: List<PolicyRuleInput>)
data class WarehousePolicyVersion(val id: UUID, val revision: Long, val currency: String, val expiryHours: Int,
    val warehouseIds: List<UUID>, val rules: List<PolicyRuleInput>, val actorId: UUID, val authorityEpoch: Long, val createdAt: Instant)
data class WarehouseScopeInput(val expectedRevision: Long, val active: Boolean)
data class WarehouseScopeGrant(val id: UUID, val userId: UUID, val locationId: UUID, val active: Boolean, val revision: Long)
data class WarehouseDelegationInput(val expectedRevision: Long, val approverId: UUID, val delegateId: UUID, val sourceRoleId: UUID?,
    val locationId: UUID, val operation: PolicyOperation, val validUntil: Instant)
data class WarehouseRevisionInput(val expectedRevision: Long)
data class WarehouseDelegation(val id: UUID, val approverId: UUID, val delegateId: UUID, val sourceRoleId: UUID?,
    val locationId: UUID, val operation: PolicyOperation, val validFrom: Instant, val validUntil: Instant,
    val revokedAt: Instant?, val revision: Long)
data class WarehouseSourceInput(val sourceDocumentId: UUID, val sourceRevision: Long)
data class PolicyEligibleApprover(val userId: UUID, val delegatedFrom: UUID? = null, val delegationId: UUID? = null)
data class PolicyTierRequirement(val number: Int, val minimumMinor: String, val approvers: List<PolicyEligibleApprover>)
data class WarehousePolicyEvaluation(val code: String, val sourceDocumentId: UUID, val sourceRevision: Long,
    val operation: PolicyOperation, val policy: WarehousePolicyVersion?, val valueNumerator: String?, val valueDenominator: String?,
    val currency: String?, val excludedUserIds: Set<UUID>, val tiers: List<PolicyTierRequirement>, val snapshotHash: String,
    val authorityEpoch: Long)

interface WarehousePolicyEvaluationApi {
    fun evaluate(source: WarehouseSourceInput): WarehousePolicyEvaluation
    fun evaluateForAction(source: WarehouseSourceInput, action: PolicyOperation): WarehousePolicyEvaluation
}
