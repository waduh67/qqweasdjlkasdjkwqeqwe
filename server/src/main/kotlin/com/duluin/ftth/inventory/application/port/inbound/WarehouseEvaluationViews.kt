package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.PolicyOperation
import java.util.UUID

sealed interface WarehouseEvaluationView {
    val code: String
    val sourceDocumentId: UUID
    val sourceRevision: Long
    val requiredAction: String
    val message: String
}

data class WarehouseOperationEvaluation(override val code: String, override val sourceDocumentId: UUID,
    override val sourceRevision: Long, override val requiredAction: String, override val message: String) : WarehouseEvaluationView

data class WarehouseApprovalEvaluation(override val code: String, override val sourceDocumentId: UUID,
    override val sourceRevision: Long, override val requiredAction: String, override val message: String,
    val policy: WarehouseEvaluationPolicy, val tiers: List<WarehouseEvaluationTier>) : WarehouseEvaluationView

data class WarehouseCostEvaluation(override val code: String, override val sourceDocumentId: UUID,
    override val sourceRevision: Long, override val requiredAction: String, override val message: String,
    val policy: WarehouseEvaluationPolicy, val tiers: List<WarehouseEvaluationTier>,
    val valueNumerator: String, val valueDenominator: String, val currency: String) : WarehouseEvaluationView

data class WarehouseEvaluationPolicy(val id: UUID, val revision: Long, val expiryHours: Int,
    val warehouseIds: List<UUID>, val operation: PolicyOperation)
data class WarehouseEvaluationTier(val number: Int, val approvers: List<WarehouseEvaluationApprover>)
sealed interface WarehouseEvaluationApprover { val userId: UUID }
data class WarehouseDirectApprover(override val userId: UUID) : WarehouseEvaluationApprover
data class WarehouseDelegatedApprover(override val userId: UUID, val delegatedFrom: UUID, val delegationId: UUID) : WarehouseEvaluationApprover
data class WarehouseEvaluationResult(val status: Int, val body: WarehouseEvaluationView)
