package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryMaterialReceiptApi {
    fun acknowledge(context: MaterialPlanningContext, request: MaterialReceiptRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun receipt(id: UUID): String
}

data class MaterialReceiptRequest(
    val issueId: UUID,
    val expectedRevision: Long,
    val workOrderRevision: Long,
    val evidenceReference: String,
    val lines: List<MaterialReceiptSelection>,
)

data class MaterialReceiptSelection(
    val issueLineId: UUID,
    val stockIdentityId: UUID,
    val baseUnit: WarehouseBaseUnit,
    val acceptedBase: String,
    val missingBase: String = "0",
    val rejectedBase: String = "0",
    val reason: String? = null,
    val serial: String? = null,
)
