package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryAssetLossApi {
    fun request(input: WarehouseAssetLossInput, metadata: WarehouseMutationMetadata): WarehouseAssetLossView
    fun get(id: UUID): WarehouseAssetLossView
    fun list(page: Int, size: Int): WarehousePage<WarehouseAssetLossView>
}

data class WarehouseAssetLossInput(val assignmentId: UUID, val sourceHandoverId: UUID,
    val expectedRevision: Long, val expectedTitleRevision: Long, val expectedWorkOrderRevision: Long,
    val destinationLocationId: UUID, val reason: String, val evidenceId: UUID)

data class WarehouseAssetLossView(val id: UUID, val code: String, val revision: Long,
    val state: WarehouseDispositionState, val assignmentId: UUID, val sourceHandoverId: UUID,
    val stockIdentityId: UUID, val sourceLocationId: UUID, val destinationLocationId: UUID,
    val quantityBase: String, val baseUnit: WarehouseBaseUnit, val reason: String,
    val evidenceId: UUID, val recordedAt: Instant)
