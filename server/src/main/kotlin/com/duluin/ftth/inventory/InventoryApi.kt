package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.domain.model.InventoryStatus
import java.util.UUID
import java.time.Instant

data class InventoryAssetRef(
    val assetId: UUID,
    val tenantId: UUID,
    val skuId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val status: InventoryStatus,
    val locationId: UUID,
    val custodyOwnerId: UUID,
    val installedOnuId: UUID?,
)

interface InventoryApi {
    fun findSerializedAsset(assetId: UUID): InventoryAssetRef?
    fun findBySerial(serialNumber: String): InventoryAssetRef?
    fun linkInstalledOnu(assetId: UUID, onuId: UUID, operationKey: String): InventoryAssetRef

    fun consumeFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult
    fun returnFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult
    fun fulfillmentAllocations(workOrderId: UUID): List<InventoryFulfillmentAllocation> = emptyList()
}

data class InventoryFulfillmentAllocation(
    val targetId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val customerId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val actorId: UUID,
    val itemCategory: String,
)

data class InventoryFulfillmentCommand(
    val tenantId: UUID,
    val targetId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val installed: Boolean,
    val actorId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val reason: String,
    val itemCategory: String = targetId.toString(),
)

data class InventoryFulfillmentResult(
    val tenantId: UUID,
    val operationKey: String,
    val targetId: UUID,
    val applied: Boolean,
    val replayed: Boolean,
    val recordedAt: Instant,
)
