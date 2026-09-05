package com.duluin.ftth.inventory.domain.model

import java.time.Instant
import java.util.UUID

enum class DiscrepancyState { OPEN, PENDING_APPROVAL, RESOLVED, REWORK_REQUIRED }

data class CycleCount(
    val countId: UUID,
    val tenantId: UUID,
    val locationId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val priorQuantity: Int,
    val observedQuantity: Int,
    val reason: String,
    val evidenceReference: String,
    val operationKey: String,
    val operationHash: String,
    val custodianId: UUID,
    val createdAt: Instant,
    val discrepancy: DiscrepancyState,
    val approverId: UUID? = null,
    val closedAt: Instant? = null,
) {
    init {
        require(priorQuantity >= 0 && observedQuantity >= 0)
        require(reason.isNotBlank() && evidenceReference.isNotBlank())
        require(operationKey.isNotBlank() && operationHash.isNotBlank())
        require(approverId == null || approverId != custodianId) { "custodian cannot approve own variance" }
        require(closedAt == null || discrepancy == DiscrepancyState.RESOLVED)
    }
}

data class ReconciliationDiscrepancy(
    val tenantId: UUID,
    val skuId: UUID,
    val serializedQuantity: Int,
    val looseQuantity: Int,
    val projectedQuantity: Int,
    val negative: Boolean,
    val doubleCounted: Boolean,
)
