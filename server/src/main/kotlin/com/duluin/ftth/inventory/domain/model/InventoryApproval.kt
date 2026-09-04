package com.duluin.ftth.inventory.domain.model

import java.time.Instant
import java.util.UUID

enum class InventoryApprovalType { RESTOCK, ISSUE_EXCEPTION, ADJUSTMENT, LOSS, SCRAP, WRITE_OFF, COUNT_VARIANCE }
enum class InventoryApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, REWORK_REQUIRED }
enum class InventoryApprovalDecision { APPROVE, REJECT }

data class ApprovalTier(val number: Int, val minimumAmount: Long, val approverIds: Set<UUID>) {
    init { require(number > 0 && minimumAmount >= 0 && approverIds.isNotEmpty()) }
}

data class InventoryApprovalPolicy(
    val version: Long,
    val tiers: List<ApprovalTier>,
    val expiry: java.time.Duration = java.time.Duration.ofHours(24),
    val emergencyAllowed: Boolean = false,
) {
    init {
        require(version > 0 && tiers.isNotEmpty() && !expiry.isNegative && !expiry.isZero)
        require(tiers.map { it.number }.distinct().size == tiers.size)
        require(tiers == tiers.sortedBy { it.minimumAmount })
    }
    fun requiredTiers(amount: Long): List<ApprovalTier> = tiers.filter { amount >= it.minimumAmount }.sortedBy { it.number }
}

data class InventoryApprovalRequest(
    val approvalId: UUID,
    val tenantId: UUID,
    val type: InventoryApprovalType,
    val amount: Long,
    val requesterId: UUID,
    val custodianId: UUID?,
    val policy: InventoryApprovalPolicy,
    val policySnapshotHash: String,
    val operationKey: String,
    val operationHash: String,
    val emergencyReason: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val status: InventoryApprovalStatus = InventoryApprovalStatus.PENDING,
    val revision: Long = 0,
    val decisions: List<InventoryApprovalDecisionSnapshot> = emptyList(),
) {
    init {
        require(amount >= 0 && operationKey.isNotBlank() && operationHash.isNotBlank())
        require(policySnapshotHash.isNotBlank())
        require(emergencyReason == null || emergencyReason.isNotBlank())
    }
    fun currentTier(): ApprovalTier? = policy.requiredTiers(amount).firstOrNull { tier -> decisions.none { it.tier == tier.number && it.decision == InventoryApprovalDecision.APPROVE } }
}

data class InventoryApprovalDecisionSnapshot(
    val decisionId: UUID,
    val tier: Int,
    val approverId: UUID,
    val delegatedFrom: UUID?,
    val decision: InventoryApprovalDecision,
    val reason: String?,
    val decidedAt: Instant,
    val revision: Long,
    val operationKey: String,
    val operationHash: String,
)

data class ApproverDelegation(val approverId: UUID, val delegateId: UUID, val validUntil: Instant)

data class InventoryApprovalEffect(
    val approvalId: UUID,
    val tenantId: UUID,
    val type: InventoryApprovalType,
    val status: InventoryApprovalStatus,
    val movementId: UUID?,
    val operationKey: String,
    val emittedAt: Instant,
)
