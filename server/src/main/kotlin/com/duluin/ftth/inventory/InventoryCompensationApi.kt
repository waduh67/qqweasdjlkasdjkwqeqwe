package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryCompensationApi {
    fun request(dispositionId: UUID, input: WarehouseCompensationInput, metadata: WarehouseMutationMetadata): WarehouseCompensationView
    fun get(dispositionId: UUID, id: UUID): WarehouseCompensationView
    fun list(dispositionId: UUID, page: WarehousePageRequest): WarehousePage<WarehouseCompensationView>
}

data class WarehouseCompensationInput(val expectedRevision: Long, val expectedReturnRevision: Long,
    val destinationLocationId: UUID, val reason: String, val evidenceReference: String)
data class WarehouseCompensationView(val id: UUID, val code: String, val revision: Long, val state: WarehouseDispositionState,
    val originalDispositionId: UUID, val originalPostingId: UUID, val returnId: UUID, val stockIdentityId: UUID,
    val quantityBase: String, val baseUnit: WarehouseBaseUnit, val sourceLocationId: UUID, val destinationLocationId: UUID,
    val reason: String, val evidenceReference: String, val recordedAt: Instant)
