package com.duluin.ftth.inventory

import java.util.UUID

interface InventorySupplierReplacementApi {
    fun request(id: UUID, input: SupplierReplacementInput, metadata: WarehouseMutationMetadata): SupplierReplacementView
    fun list(id: UUID, page: WarehousePageRequest): List<SupplierReplacementView>
}

data class SupplierReplacementInput(val expectedRevision: Long, val externalReference: String,
    val sourceLocationId: UUID, val inspectionLocationId: UUID, val skuId: UUID, val serial: String,
    val evidenceReference: String, val mac: String? = null,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val cost: SupplierReplacementCost? = null)
data class SupplierReplacementCost(val totalMinor: String, val currency: String)
data class SupplierReplacementView(val id: UUID, val returnId: UUID, val repairCaseId: UUID,
    val receiptId: UUID, val originalAssetId: UUID, val legalOwner: AssetLegalOwner,
    val replacementAssetId: UUID? = null)
