package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.util.UUID

abstract class UnavailableMaterialActions : InventoryMaterialApi {
    override fun pick(workOrderId: UUID, request: PickMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = unavailable()
    override fun dispatch(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = unavailable()
    override fun acknowledge(workOrderId: UUID, request: AcknowledgeMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = unavailable()
    override fun reportUse(workOrderId: UUID, request: ReportMaterialUseRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = unavailable()
    override fun returnMaterial(workOrderId: UUID, request: ReturnMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = unavailable()
    override fun reallocate(workOrderId: UUID, request: ReallocateMaterialRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = unavailable()
    override fun verifySettlement(workOrderId: UUID, request: SettlementCheckRequest, metadata: WarehouseMutationMetadata): MaterialSettlementSnapshot = unavailable()
    private fun unavailable(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED,
        "This material transition is not available; task13 only supports planning, submission, reserve and release"))
}
