package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryMaterialUsageApi {
    fun reportUse(context: MaterialPlanningContext, request: MaterialUsageRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun usage(id: UUID): String
}

data class MaterialUsageRequest(
    val expectedRevision: Long,
    val planRevision: Long,
    val workOrderRevision: Long,
    val materialMode: MaterialMode,
    val evidenceReference: String,
    val lines: List<MaterialUsageSelection>,
    val reason: String? = null,
    val networkReferenceLabel: String? = null,
)

data class MaterialUsageSelection(
    val receiptId: UUID,
    val issueLineId: UUID,
    val stockIdentityId: UUID,
    val quantityBase: String,
    val baseUnit: WarehouseBaseUnit,
)
