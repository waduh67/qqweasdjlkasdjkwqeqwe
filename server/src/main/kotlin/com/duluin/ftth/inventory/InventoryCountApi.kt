package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryCountApi {
    fun create(input: WarehouseCountDraft, key: String): WarehouseOperationReceipt
    fun start(id: UUID, input: WarehouseCountRevision, key: String): WarehouseOperationReceipt
    fun observe(id: UUID, input: WarehouseCountObservation, key: String): WarehouseOperationReceipt
    fun submit(id: UUID, input: WarehouseCountRevision, key: String): WarehouseOperationReceipt
    fun recount(id: UUID, input: WarehouseCountRevision, key: String): WarehouseOperationReceipt
    fun get(id: UUID): WarehouseCountView
    fun history(id: UUID): List<WarehouseCountFact>
    fun list(page: Int, size: Int): WarehousePage<WarehouseCountView>
}

data class WarehouseCountAssignment(val balanceId: UUID, val counterId: UUID)
data class WarehouseCountDraft(val locationId: UUID, val partialLocation: Boolean, val reason: String,
    val entries: List<WarehouseCountAssignment>)
data class WarehouseCountRevision(val expectedRevision: Long)
data class WarehouseCountObservation(val expectedRevision: Long, val balanceId: UUID, val quantityBase: String,
    val reason: String, val documentReference: String)
data class WarehouseCountEntry(val balanceId: UUID, val counterId: UUID, val stockIdentityId: UUID,
    val skuId: UUID, val baseUnit: WarehouseBaseUnit)
data class WarehouseCountView(val id: UUID, val revision: Long, val state: WarehouseCountState, val locationId: UUID,
    val partialLocation: Boolean, val roundRevision: Long?, val entries: List<WarehouseCountEntry>)
data class WarehouseCountFact(val id: UUID, val balanceId: UUID, val counterId: UUID, val roundRevision: Long,
    val quantityBase: String, val baseUnit: WarehouseBaseUnit, val reason: String, val documentReference: String)
