package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryTransferApi {
    fun create(request: WarehouseTransferDraft, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun dispatch(id: UUID, request: WarehouseTransferRevision, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun receive(id: UUID, request: WarehouseTransferReceipt, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun get(id: UUID): WarehouseTransferView
    fun history(id: UUID): List<WarehouseTransferView>
}

data class WarehouseTransferDraft(val sourceLocationId: UUID, val destinationLocationId: UUID,
    val transitLocationId: UUID, val receiverId: UUID, val reason: String, val lines: List<WarehouseTransferSelection>)
data class WarehouseTransferSelection(val stockIdentityId: UUID, val quantityBase: String, val baseUnit: WarehouseBaseUnit)
data class WarehouseTransferRevision(val expectedRevision: Long)
data class WarehouseTransferReceipt(val expectedRevision: Long, val evidenceReference: String,
    val lines: List<WarehouseTransferAcceptance>)
data class WarehouseTransferAcceptance(val lineId: UUID, val quantityBase: String, val baseUnit: WarehouseBaseUnit)

data class WarehouseTransferView(val id: UUID, val code: String, val revision: Long, val state: WarehouseTransferState,
    val sourceLocationId: UUID, val destinationLocationId: UUID, val transitLocationId: UUID,
    val senderId: UUID, val receiverId: UUID, val reason: String, val recordedAt: Instant,
    val lines: List<WarehouseTransferLineView>)
data class WarehouseTransferLineView(val id: UUID, val skuId: UUID, val stockIdentityId: UUID,
    val baseUnit: WarehouseBaseUnit, val quantityBase: String, val receivedBase: String, val inTransitBase: String,
    val remainingIdentityId: UUID?, val condition: WarehouseCondition, val legalOwner: AssetLegalOwner)
