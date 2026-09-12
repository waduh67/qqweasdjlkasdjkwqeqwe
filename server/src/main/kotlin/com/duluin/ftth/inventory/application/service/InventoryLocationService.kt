package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.domain.model.InventoryLocation
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Gudang, bin, kendaraan, dan tempat-tempat lain yang bisa memegang stok.
 *
 * Sebelum ini `inventory_location` hanya bisa diisi lewat migrasi atau SQL manual, padahal
 * setiap mutasi stok, saldo, dan aset serial menunjuk `location_id`. Artinya tenant baru
 * tidak punya satu pun tempat untuk menaruh barang dan seluruh permukaan gudang mati sejak
 * hari pertama.
 */
@Service
class InventoryLocationService(private val locations: InventoryLocationRepository) {

    @Transactional
    fun create(command: CreateInventoryLocation): InventoryLocation {
        val code = normalize(command.code)
        if (locations.findByCode(command.tenantId, code) != null) {
            throw ConflictException("Kode lokasi $code sudah dipakai")
        }
        validateParent(command.tenantId, command.kind, command.parentId)
        return locations.save(InventoryLocation(UuidV7.generate(), command.tenantId, code, command.kind, command.parentId))
    }

    @Transactional
    fun update(locationId: UUID, tenantId: UUID, command: UpdateInventoryLocation): InventoryLocation {
        val current = load(locationId, tenantId)
        val code = normalize(command.code)
        val clash = locations.findByCode(tenantId, code)
        if (clash != null && clash.id != locationId) throw ConflictException("Kode lokasi $code sudah dipakai")
        // Jenis TIDAK ikut berubah: saldo dan aset yang sudah menempel pada lokasi ini
        // ditulis dengan asumsi jenisnya (mis. leg bercustody WAREHOUSE), dan mengubah
        // gudang jadi kendaraan membuat seluruh baris lama salah arti tanpa satu pun mutasi
        // yang menjelaskannya.
        validateParent(tenantId, current.kind, command.parentId)
        if (command.parentId == locationId) throw ValidationException("Lokasi tidak boleh menjadi induk dirinya sendiri")
        return locations.save(current.copy(code = code, parentId = command.parentId))
    }

    @Transactional(readOnly = true)
    fun list(tenantId: UUID, kind: LocationKind? = null): List<InventoryLocation> =
        locations.findAll(tenantId).filter { kind == null || it.kind == kind }.sortedBy { it.code }

    @Transactional(readOnly = true)
    fun get(locationId: UUID, tenantId: UUID): InventoryLocation = load(locationId, tenantId)

    /**
     * Bin WAJIB menempel pada gudang; gudang tidak boleh punya induk.
     *
     * Kedalaman dibatasi dua tingkat SENGAJA: begitu bin boleh punya bin, kode ini butuh
     * deteksi siklus dan laporan stok per gudang harus menelusuri pohon yang tidak punya
     * batas. Kebutuhan nyatanya cuma "rak di dalam gudang", dan dua tingkat cukup.
     */
    private fun validateParent(tenantId: UUID, kind: LocationKind, parentId: UUID?) {
        if (kind == LocationKind.BIN) {
            val parent = parentId?.let { load(it, tenantId) } ?: throw ValidationException("Bin wajib menyebut gudang induknya")
            if (parent.kind != LocationKind.WAREHOUSE) throw ValidationException("Induk bin harus berjenis gudang")
            return
        }
        if (parentId != null) throw ValidationException("Hanya bin yang boleh punya induk")
    }

    private fun normalize(code: String): String {
        val normalized = code.trim().uppercase()
        // Huruf kecil dan spasi dinormalkan, bukan ditolak, supaya "wh-01" dan "WH-01 " tidak
        // pernah jadi dua lokasi berbeda yang saldonya terbelah diam-diam. Panjangnya dijaga
        // agar cocok dengan varchar(64) kolomnya — kalau tidak, kegagalannya muncul sebagai
        // error SQL mentah, bukan pesan yang bisa dibaca operator.
        if (normalized.isEmpty() || normalized.length > 64) throw ValidationException("Kode lokasi wajib diisi, maksimal 64 karakter")
        return normalized
    }

    private fun load(locationId: UUID, tenantId: UUID): InventoryLocation {
        val location = locations.findById(locationId) ?: throw NotFoundException("Lokasi gudang tidak ditemukan")
        if (location.tenantId != tenantId) throw NotFoundException("Lokasi gudang tidak ditemukan")
        return location
    }
}

data class CreateInventoryLocation(
    val tenantId: UUID,
    val code: String,
    val kind: LocationKind,
    val parentId: UUID? = null,
)

data class UpdateInventoryLocation(val code: String, val parentId: UUID? = null)
