package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.InventoryApi
import com.duluin.ftth.inventory.InventoryAssetRef
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.inventory.InventoryFulfillmentResult
import com.duluin.ftth.inventory.InventoryFulfillmentAllocation
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InventoryApiService(
    private val assets: SerializedAssetRepository,
    private val durableFulfillment: DurableInventoryFulfillmentService,
    private val allocationReader: com.duluin.ftth.inventory.InventoryReservationApi,
) : InventoryApi {
    @Transactional(readOnly = true)
    override fun findSerializedAsset(assetId: UUID): InventoryAssetRef? = assets.findById(assetId)?.toRef()

    @Transactional(readOnly = true)
    override fun findBySerial(serialNumber: String): InventoryAssetRef? = assets.findBySerial(TenantContext.tenantId(), serialNumber.trim())?.toRef()

    @Transactional
    override fun linkInstalledOnu(assetId: UUID, onuId: UUID, operationKey: String): InventoryAssetRef {
        throw WarehouseContractException(WarehouseError(WarehouseErrorCode.USE_WORKORDER_ASSET_WORKFLOW, "USE_WORKORDER_ASSET_WORKFLOW"))
    }

    @Transactional
    override fun consumeFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult =
        durableFulfillment.apply(command, returned = false)

    @Transactional
    override fun returnFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult =
        durableFulfillment.apply(command, returned = true)

    override fun fulfillmentAllocations(workOrderId: UUID): List<InventoryFulfillmentAllocation> = allocationReader.allocations(workOrderId).also {
        if (it.isEmpty()) throw com.duluin.ftth.inventory.WarehouseContractException(com.duluin.ftth.inventory.WarehouseError(
            com.duluin.ftth.inventory.WarehouseErrorCode.INSUFFICIENT_STOCK, "Material demand has no durable allocation"))
    }.map { allocation ->
        val total = Math.addExact(allocation.reservedUnpickedBase.toLong(), allocation.reservedPickedBase.toLong())
        InventoryFulfillmentAllocation(allocation.allocationId, allocation.stockIdentityId, allocation.skuId, allocation.locationId,
            allocation.customerId, if (allocation.baseUnit == com.duluin.ftth.inventory.WarehouseBaseUnit.EA && total <= Int.MAX_VALUE) total.toInt() else null,
            allocation.lotId == null, allocation.actorId, allocation.itemCategory, allocation)
    }

    private fun com.duluin.ftth.inventory.domain.model.SerializedAsset.toRef() = InventoryAssetRef(
        id, tenantId, skuId, serialNumber, macAddress, status, locationId, custody.ownerId, installedOnuId,
    )
}

data class InventoryLocationView(val id: UUID, val code: String, val kind: String)
data class InventoryItemView(val id: UUID, val skuId: UUID, val serialNumber: String, val macAddress: String?, val status: String)
data class InventoryStockView(val skuId: UUID, val locationId: UUID, val quantities: Map<InventoryStatus, Int>)
data class InventoryReservationView(val assetId: UUID, val skuId: UUID, val locationId: UUID, val custodianId: UUID)
data class InventoryCustodyView(val assetId: UUID, val skuId: UUID, val status: String, val ownerKind: String, val ownerId: UUID, val locationId: UUID)
