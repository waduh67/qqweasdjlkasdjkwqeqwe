package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.InventoryApi
import com.duluin.ftth.inventory.InventoryAssetRef
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.inventory.InventoryFulfillmentResult
import com.duluin.ftth.inventory.InventoryFulfillmentAllocation
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InventoryApiService(
    private val assets: SerializedAssetRepository,
    private val durableFulfillment: DurableInventoryFulfillmentService,
    private val locations: InventoryLocationRepository? = null,
) : InventoryApi {
    @Transactional(readOnly = true)
    fun locations(): List<InventoryLocationView> = (locations ?: error("inventory location query is not configured")).findAll(TenantContext.tenantId()).map { InventoryLocationView(it.id, it.code, it.kind.name) }

    @Transactional(readOnly = true)
    fun items(): List<InventoryItemView> = assets.findAll(TenantContext.tenantId()).map { InventoryItemView(it.id, it.skuId, it.serialNumber, it.macAddress, it.status.name) }

    @Transactional(readOnly = true)
    fun stock(): List<InventoryStockView> = assets.findAll(TenantContext.tenantId()).groupBy { it.skuId to it.locationId }
        .map { (key, rows) -> InventoryStockView(key.first, key.second, rows.groupingBy { it.status }.eachCount()) }

    @Transactional(readOnly = true)
    fun reservations(): List<InventoryReservationView> = assets.findAll(TenantContext.tenantId()).filter { it.status == InventoryStatus.RESERVED }
        .map { InventoryReservationView(it.id, it.skuId, it.locationId, it.custody.ownerId) }

    @Transactional(readOnly = true)
    fun custody(): List<InventoryCustodyView> = assets.findAll(TenantContext.tenantId())
        .map { InventoryCustodyView(it.id, it.skuId, it.status.name, it.custody.ownerKind.name, it.custody.ownerId, it.locationId) }
    @Transactional(readOnly = true)
    override fun findSerializedAsset(assetId: UUID): InventoryAssetRef? = assets.findById(assetId)?.toRef()

    @Transactional(readOnly = true)
    override fun findBySerial(serialNumber: String): InventoryAssetRef? = assets.findBySerial(TenantContext.tenantId(), serialNumber.trim())?.toRef()

    @Transactional
    override fun linkInstalledOnu(assetId: UUID, onuId: UUID, operationKey: String): InventoryAssetRef {
        require(operationKey.isNotBlank()) { "operation key is required" }
        val tenantId = TenantContext.tenantId()
        val existing = assets.findByOperation(tenantId, operationKey)
        if (existing != null) return existing.toRef()
        val asset = assets.findById(assetId) ?: error("serialized asset not found")
        require(asset.tenantId == tenantId) { "asset belongs to another tenant" }
        require(asset.installedOnuId == null || asset.installedOnuId == onuId) { "ONU already owns another asset" }
        val saved = assets.save(asset.linkInstalledOnu(onuId))
        return saved.toRef()
    }

    @Transactional
    override fun consumeFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult =
        durableFulfillment.apply(command, returned = false)

    @Transactional
    override fun returnFulfillment(command: InventoryFulfillmentCommand): InventoryFulfillmentResult =
        durableFulfillment.apply(command, returned = true)

    override fun fulfillmentAllocations(workOrderId: UUID): List<InventoryFulfillmentAllocation> = emptyList()

    private fun com.duluin.ftth.inventory.domain.model.SerializedAsset.toRef() = InventoryAssetRef(
        id, tenantId, skuId, serialNumber, macAddress, status, locationId, custody.ownerId, installedOnuId,
    )
}

data class InventoryLocationView(val id: UUID, val code: String, val kind: String)
data class InventoryItemView(val id: UUID, val skuId: UUID, val serialNumber: String, val macAddress: String?, val status: String)
data class InventoryStockView(val skuId: UUID, val locationId: UUID, val quantities: Map<InventoryStatus, Int>)
data class InventoryReservationView(val assetId: UUID, val skuId: UUID, val locationId: UUID, val custodianId: UUID)
data class InventoryCustodyView(val assetId: UUID, val skuId: UUID, val status: String, val ownerKind: String, val ownerId: UUID, val locationId: UUID)
