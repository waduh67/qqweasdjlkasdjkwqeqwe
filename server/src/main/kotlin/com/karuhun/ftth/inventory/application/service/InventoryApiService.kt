package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.inventory.InventoryApi
import com.karuhun.ftth.inventory.InventoryAssetRef
import com.karuhun.ftth.inventory.InventoryFulfillmentCommand
import com.karuhun.ftth.inventory.InventoryFulfillmentResult
import com.karuhun.ftth.inventory.InventoryFulfillmentAllocation
import com.karuhun.ftth.inventory.WarehouseContractException
import com.karuhun.ftth.inventory.WarehouseError
import com.karuhun.ftth.inventory.WarehouseErrorCode
import com.karuhun.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.karuhun.ftth.inventory.domain.model.InventoryStatus
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InventoryApiService(
    private val assets: SerializedAssetRepository,
    private val durableFulfillment: DurableInventoryFulfillmentService,
    private val allocationReader: com.karuhun.ftth.inventory.InventoryReservationApi,
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
        if (it.isEmpty()) throw com.karuhun.ftth.inventory.WarehouseContractException(com.karuhun.ftth.inventory.WarehouseError(
            com.karuhun.ftth.inventory.WarehouseErrorCode.INSUFFICIENT_STOCK, "Material demand has no durable allocation"))
    }.map { allocation ->
        val total = Math.addExact(allocation.reservedUnpickedBase.toLong(), allocation.reservedPickedBase.toLong())
        InventoryFulfillmentAllocation(allocation.allocationId, allocation.stockIdentityId, allocation.skuId, allocation.locationId,
            allocation.customerId, if (allocation.baseUnit == com.karuhun.ftth.inventory.WarehouseBaseUnit.EA && total <= Int.MAX_VALUE) total.toInt() else null,
            allocation.lotId == null, allocation.actorId, allocation.itemCategory, allocation)
    }

    private fun com.karuhun.ftth.inventory.domain.model.SerializedAsset.toRef() = InventoryAssetRef(
        id, tenantId, skuId, serialNumber, macAddress, status, locationId, custody.ownerId, installedOnuId,
    )
}

data class InventoryLocationView(val id: UUID, val code: String, val kind: String)
data class InventoryItemView(val id: UUID, val skuId: UUID, val serialNumber: String, val macAddress: String?, val status: String)
data class InventoryStockView(val skuId: UUID, val locationId: UUID, val quantities: Map<InventoryStatus, Int>)
data class InventoryReservationView(val assetId: UUID, val skuId: UUID, val locationId: UUID, val custodianId: UUID)
data class InventoryCustodyView(val assetId: UUID, val skuId: UUID, val status: String, val ownerKind: String, val ownerId: UUID, val locationId: UUID)
