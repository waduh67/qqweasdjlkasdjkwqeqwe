package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.InventoryLocation
import com.duluin.ftth.inventory.domain.model.SerializedAsset
import java.util.UUID

interface InventoryLocationRepository {
    fun findById(id: UUID): InventoryLocation?
    fun findAll(tenantId: UUID): List<InventoryLocation> = emptyList()
    fun save(location: InventoryLocation): InventoryLocation
}

interface SerializedAssetRepository {
    fun findById(id: UUID): SerializedAsset?
    fun findAll(tenantId: UUID): List<SerializedAsset> = emptyList()
    fun findBySerial(tenantId: UUID, serialNumber: String): SerializedAsset?
    fun findByMac(tenantId: UUID, macAddress: String): SerializedAsset?
    fun save(asset: SerializedAsset): SerializedAsset
    fun existsHistoricalSerial(tenantId: UUID, serialNumber: String): Boolean
    fun existsHistoricalMac(tenantId: UUID, macAddress: String): Boolean
    fun findByOperation(tenantId: UUID, operationKey: String): SerializedAsset?
}
