package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryReturnApi {
    fun receive(request: WarehouseReturnIntake, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun inspect(id: UUID, request: WarehouseReturnInspection, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun get(id: UUID): WarehouseReturnView
    fun history(id: UUID): List<WarehouseReturnView>
}

enum class WarehouseReturnOrigin { MATERIAL_RESIDUAL, ASSET_REMOVAL }

data class WarehouseReturnIntake(val origin: WarehouseReturnOrigin, val sourceDocumentId: UUID,
    val quarantineLocationId: UUID, val evidenceReference: String)
data class WarehouseReturnInspection(val expectedRevision: Long, val measuredQuantityBase: String,
    val condition: WarehouseCondition, val destinationLocationId: UUID, val evidenceReference: String,
    val resetConfirmed: Boolean, val observedSerial: String? = null, val resetEvidenceReference: String? = null)
data class WarehouseReturnView(val id: UUID, val revision: Long, val state: WarehouseReturnState,
    val origin: WarehouseReturnOrigin, val sourceDocumentId: UUID, val stockIdentityId: UUID,
    val skuId: UUID, val lotId: UUID?, val baseUnit: WarehouseBaseUnit, val quantityBase: String,
    val locationId: UUID, val condition: WarehouseCondition, val legalOwner: AssetLegalOwner,
    val receivedBy: UUID, val recordedAt: Instant, val inspection: WarehouseReturnInspection? = null)
