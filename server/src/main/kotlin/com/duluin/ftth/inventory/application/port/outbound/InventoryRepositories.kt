package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.InventoryLocation
import com.duluin.ftth.inventory.domain.model.SerializedAsset
import java.util.UUID

interface InventoryLocationRepository {
    fun findById(id: UUID): InventoryLocation?
    fun findAll(tenantId: UUID): List<InventoryLocation> = emptyList()
    /**
     * Dipakai untuk menolak kode ganda dengan pesan yang bisa dibaca operator. UNIQUE
     * `inventory_location_tenant_code_uq` tetap penjaga terakhirnya — pemeriksaan ini bisa
     * disalip request bersamaan, dan yang kalah memang harus gagal di basis data.
     */
    fun findByCode(tenantId: UUID, code: String): InventoryLocation? = null
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
    /**
     * Hapus baris aset. SATU-SATUNYA pemakainya adalah pembatalan restock berserial: unit yang
     * dijanjikan pemasok tapi kirimannya ditolak tidak pernah ada secara fisik, dan
     * membiarkannya hidup akan membakar nomor serinya selamanya di [existsHistoricalSerial].
     *
     * BUKAN jalan untuk membuang barang: barang nyata dihapusbukukan lewat mutasi
     * LOSS/SCRAP/WRITE_OFF supaya ada jejaknya. Riwayat mutasi tidak terganggu — leg menyimpan
     * salinan nomor serinya sendiri (V174).
     */
    fun delete(assetId: UUID)
    fun existsHistoricalSerial(tenantId: UUID, serialNumber: String): Boolean
    fun existsHistoricalMac(tenantId: UUID, macAddress: String): Boolean
    fun findByOperation(tenantId: UUID, operationKey: String): SerializedAsset?
}
