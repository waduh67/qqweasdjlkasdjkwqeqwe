package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryMaterialReservationApi {
    fun reserve(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun release(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
}

interface InventoryMaterialApi : InventoryMaterialReservationApi {
    fun summary(workOrderId: UUID): MaterialSummary
    fun history(workOrderId: UUID, page: WarehousePageRequest): WarehousePage<WarehouseHistoryEntry>
    fun replacePlan(workOrderId: UUID, request: ReplaceMaterialPlanRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun submitRequest(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    override fun reserve(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    override fun release(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun pick(workOrderId: UUID, request: PickMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun dispatch(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun acknowledge(workOrderId: UUID, request: AcknowledgeMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun reportUse(workOrderId: UUID, request: ReportMaterialUseRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun returnMaterial(workOrderId: UUID, request: ReturnMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun reallocate(workOrderId: UUID, request: ReallocateMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun verifySettlement(workOrderId: UUID, request: SettlementCheckRequest, metadata: WarehouseMutationMetadata): MaterialSettlementSnapshot
}

enum class MaterialMode { NONE, MATERIAL_REQUIRED }
enum class MaterialSettlementState { OPEN, RESIDUAL_PENDING, CLOSED }
enum class MaterialQaState { PENDING, APPROVED, REJECTED }
enum class MaterialInstallationState { NOT_APPLICABLE, NOT_INSTALLED, PROVISIONAL, INSTALLED, REMOVED }
enum class MaterialProvisioningState { NOT_APPLICABLE, PENDING, SUCCEEDED, FAILED }
enum class MaterialEffect { SETTLEMENT_VERIFY }

data class MaterialRevisions(
    val workOrderRevision: Long,
    val planRevision: Long,
    val useRevision: Long,
    val settlementRevision: Long,
)

data class ReplaceMaterialPlanRequest(
    val expectedRevision: Long,
    val materialMode: MaterialMode,
    val reason: String?,
    val lines: List<MaterialPlanLine>,
)

data class MaterialPlanLine(
    val skuId: UUID,
    val quantityBase: String,
    val baseUnit: WarehouseBaseUnit,
    val continuousCut: Boolean = true,
)

data class ReportMaterialUseRequest(
    val expectedRevision: Long,
    val planRevision: Long,
    val lines: List<MaterialUseLine>,
)

data class MaterialUseLine(
    val issueLineId: UUID,
    val stockIdentityId: UUID,
    val quantityBase: String,
    val baseUnit: WarehouseBaseUnit,
)

data class SettlementCheckRequest(val expectedRevision: Long, val planRevision: Long, val useRevision: Long)

data class MaterialLineTotals(
    val planLineId: UUID,
    val skuId: UUID,
    val baseUnit: WarehouseBaseUnit,
    val requestedBase: String,
    val reservedUnpickedBase: String,
    val reservedPickedBase: String,
    val issuedBase: String,
    val physicallyUsedBase: String,
    val returnedBase: String,
    val transferredOutBase: String,
    val disposedBase: String,
    val stillAccountableBase: String,
)

data class MaterialSummary(
    val workOrderId: UUID,
    val materialMode: MaterialMode,
    val noMaterialReason: String?,
    val revisions: MaterialRevisions,
    val demandState: MaterialDemandState,
    val installationState: MaterialInstallationState,
    val qaState: MaterialQaState,
    val provisioningState: MaterialProvisioningState,
    val settlementState: MaterialSettlementState,
    val lines: List<MaterialLineTotals>,
)

data class MaterialSettlementSnapshot(
    val workOrderId: UUID,
    val revisions: MaterialRevisions,
    val effect: MaterialEffect,
    val state: MaterialSettlementState,
    val referencedPostingIds: List<UUID>,
    val residuals: List<MaterialLineTotals>,
    val receipt: WarehouseOperationReceipt,
)
