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
    /**
     * [operationKey] IKUT disimpan (kolom `last_operation_key`, V144) supaya [findByOperation]
     * bisa menemukan hasil percobaan sebelumnya. Tanpa itu, teknisi yang menekan "pasang ONU"
     * dua kali karena sinyal putus akan menjalankan efeknya dua kali — idempotency-nya hanya
     * berlaku kalau kuncinya benar-benar mendarat di baris aset.
     */
    fun save(asset: SerializedAsset, operationKey: String? = null): SerializedAsset
    fun existsHistoricalSerial(tenantId: UUID, serialNumber: String): Boolean
    fun existsHistoricalMac(tenantId: UUID, macAddress: String): Boolean
    fun findByOperation(tenantId: UUID, operationKey: String): SerializedAsset?
}
