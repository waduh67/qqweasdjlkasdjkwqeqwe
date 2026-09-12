package com.duluin.ftth.inventory.domain.model

import java.util.UUID

enum class InventoryStatus {
    AVAILABLE, RESERVED, ISSUED, IN_TRANSIT, CONSUMED, RETURNED, QUARANTINE, LOST, DISPOSED
}

enum class LocationKind { WAREHOUSE, BIN, VEHICLE, TECHNICIAN, CUSTOMER_SITE, QUARANTINE, LOST, DISPOSED, TRANSIT }

data class InventoryLocation(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val kind: LocationKind,
    /**
     * Gudang induk (V181). Wajib untuk BIN dan harus null untuk WAREHOUSE — aturannya
     * ditegakkan di `InventoryLocationService` karena ia butuh membaca jenis induknya.
     * Tanpa kolom ini, bin adalah pulau yang tidak menempel pada gudang mana pun dan
     * laporan stok per gudang tidak pernah bisa menjumlahkan isi bin-binnya.
     */
    val parentId: UUID? = null,
) {
    init {
        require(code.trim().isNotEmpty()) { "location code is required" }
        require(parentId != id) { "location cannot be its own parent" }
    }
}

// Master data barang gudang sekarang tinggal di `InventoryItem` (tabel `inventory_item`,
// V173). `Sku` dan `Lot` yang dulu di sini DIHAPUS: keduanya tidak punya tabel, tidak punya
// repository, dan tidak pernah direferensikan satu baris kode pun — membiarkannya hanya
// membuat pembaca mengira master data gudang sudah ada padahal belum.

data class CustodyClaim(
    val ownerId: UUID,
    val ownerKind: OwnerKind,
    val locationId: UUID?,
)

enum class OwnerKind { WAREHOUSE, VEHICLE, TECHNICIAN, CUSTOMER, REPAIR, TRANSIT, LOST, DISPOSED }

data class SerializedAsset(
    val id: UUID,
    val tenantId: UUID,
    val skuId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val status: InventoryStatus,
    val locationId: UUID,
    val custody: CustodyClaim,
    val installedOnuId: UUID? = null,
) {
    init {
        require(serialNumber.trim().isNotEmpty()) { "serial number is required" }
        require(macAddress == null || MAC.matches(macAddress)) { "invalid MAC address" }
        require(custody.locationId == locationId || custody.ownerKind == OwnerKind.TRANSIT) {
            "custody location must match asset location"
        }
        require(status != InventoryStatus.DISPOSED || custody.ownerKind == OwnerKind.DISPOSED) {
            "disposed asset must have disposed custody"
        }
    }

    fun transition(to: InventoryStatus, destination: InventoryLocation, nextCustody: CustodyClaim): SerializedAsset {
        require(destination.tenantId == tenantId) { "destination belongs to another tenant" }
        require(nextCustody.locationId == destination.id || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "custody does not claim destination"
        }
        require(isAllowed(status, to)) { "invalid inventory transition $status -> $to" }
        require(to != InventoryStatus.IN_TRANSIT || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "in-transit asset requires transit custody"
        }
        require(to != InventoryStatus.DISPOSED || nextCustody.ownerKind == OwnerKind.DISPOSED) {
            "disposed asset requires disposed custody"
        }
        return copy(status = to, locationId = destination.id, custody = nextCustody)
    }

    /**
     * Pindah tempat TANPA ganti status — untuk transfer antar gudang/bin.
     *
     * Tidak bisa memakai [transition] karena tabel transisinya menolak AVAILABLE -> AVAILABLE:
     * ia menjaga perubahan STATUS, bukan perpindahan tempat. Kalau transfer dipaksa lewat
     * sana, satu-satunya jalan yang tersisa adalah menurunkan status ke IN_TRANSIT lalu
     * menaikkannya lagi — dua mutasi untuk satu perpindahan nyata, dan aset yang gagal di
     * langkah kedua tersangkut IN_TRANSIT di gudang yang sudah menerimanya secara fisik.
     */
    fun relocate(destination: InventoryLocation, nextCustody: CustodyClaim): SerializedAsset {
        require(destination.tenantId == tenantId) { "destination belongs to another tenant" }
        require(nextCustody.locationId == destination.id || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "custody does not claim destination"
        }
        require(status != InventoryStatus.CONSUMED && status != InventoryStatus.DISPOSED) {
            "consumed or disposed asset cannot be relocated"
        }
        return copy(locationId = destination.id, custody = nextCustody)
    }

    fun linkInstalledOnu(onuId: UUID): SerializedAsset {
        require(status == InventoryStatus.ISSUED || status == InventoryStatus.CONSUMED) {
            "only issued or consumed assets can be linked to an ONU"
        }
        require(installedOnuId == null || installedOnuId == onuId) { "asset already linked to another ONU" }
        return copy(installedOnuId = onuId)
    }

    companion object {
        private val MAC = Regex("(?i)^[0-9a-f]{2}([-:])[0-9a-f]{2}(\\1[0-9a-f]{2}){4}$")
        private fun isAllowed(from: InventoryStatus, to: InventoryStatus): Boolean = when (from) {
            InventoryStatus.AVAILABLE -> to in setOf(InventoryStatus.RESERVED, InventoryStatus.ISSUED, InventoryStatus.IN_TRANSIT, InventoryStatus.QUARANTINE, InventoryStatus.LOST)
            InventoryStatus.RESERVED -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.ISSUED, InventoryStatus.IN_TRANSIT)
            InventoryStatus.ISSUED -> to in setOf(InventoryStatus.CONSUMED, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE, InventoryStatus.LOST)
            InventoryStatus.IN_TRANSIT -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.ISSUED, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE, InventoryStatus.LOST)
            InventoryStatus.RETURNED -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.QUARANTINE)
            InventoryStatus.QUARANTINE, InventoryStatus.LOST -> to == InventoryStatus.DISPOSED
            InventoryStatus.CONSUMED, InventoryStatus.DISPOSED -> false
        }
    }
}

class InventoryInvariantException(message: String) : IllegalArgumentException(message)
