package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryMaterialLifecycleApi {
    fun participates(workOrderId: UUID): Boolean
    fun summary(context: MaterialPlanningContext): MaterialObligations
    fun beforeChange(context: MaterialPlanningContext, change: MaterialLifecycleAction)
    fun close(context: MaterialPlanningContext, request: MaterialCloseRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun dispatch(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun acknowledge(context: MaterialPlanningContext, request: MaterialResidualAcknowledgement, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun authorizeHandover(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun correctUse(context: MaterialPlanningContext, request: MaterialUsageDeltaRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
}

enum class MaterialLifecycleAction { CANCEL, REASSIGN, REWORK, RESUBMIT, CLOSE }
enum class ResidualPurpose { RETURN, HANDOVER }
enum class ResidualSettlementState { OPEN, SETTLING, CLOSED, OVERDUE }

data class MaterialCloseRequest(val expectedRevision: Long, val workOrderRevision: Long, val reason: String)
data class MaterialResidualRequest(
    val workOrderRevision: Long,
    val receiptId: UUID,
    val issueLineId: UUID,
    val stockIdentityId: UUID,
    val quantityBase: String,
    val baseUnit: WarehouseBaseUnit,
    val targetLocationId: UUID,
    val reason: String,
    val evidenceReference: String,
    val usageId: UUID? = null,
    val authorizationId: UUID? = null,
)
data class MaterialResidualAcknowledgement(val documentId: UUID, val expectedRevision: Long, val evidenceReference: String)
data class MaterialUsageDeltaRequest(val expectedRevision: Long, val workOrderRevision: Long, val previousUsageId: UUID,
    val receiptId: UUID, val issueLineId: UUID, val stockIdentityId: UUID, val quantityBase: String,
    val baseUnit: WarehouseBaseUnit, val evidenceReference: String, val reason: String,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val reworkId: UUID? = null,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val evidenceRevision: String? = null)
data class MaterialObligationLine(
    val issueLineId: UUID, val stockIdentityId: UUID, val baseUnit: WarehouseBaseUnit,
    val issuedBase: String, val usedBase: String, val returnedBase: String,
    val transferredBase: String, val disposedBase: String, val stillAccountableBase: String,
    val transitBase: String, val acknowledgedBase: String,
)
data class MaterialObligations(
    val workOrderId: UUID, val revision: Long, val materialState: ResidualSettlementState,
    val dueAt: Instant?, val outstandingBase: String, val reservedUnpickedBase: String,
    val pickedBase: String, val lines: List<MaterialObligationLine>,
)
