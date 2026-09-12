package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.domain.model.InventoryItem
import com.duluin.ftth.inventory.domain.model.InventoryItemCategory
import com.duluin.ftth.inventory.domain.model.InventoryUnit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * Master data barang gudang.
 *
 * Tabel `inventory_item` sudah ada sejak V173 tapi sampai sekarang tidak punya satu pun
 * jalan masuk: tidak ada endpoint, tidak ada service, sehingga seluruh `item_id` di ledger
 * dan saldo adalah UUID yang tak bisa diterjemahkan jadi nama barang oleh siapa pun. Kelas
 * ini yang membuat master data itu benar-benar bisa diisi.
 */
@Service
class InventoryItemService(private val items: InventoryItemRepository) {

    @Transactional
    fun create(command: CreateInventoryItem): InventoryItem {
        val normalized = command.code.trim().uppercase()
        // Diperiksa lebih dulu supaya operator mendapat pesan yang bisa dibaca. Penjaga
        // sebenarnya tetap UNIQUE (tenant_id, code) di basis data: dua request bersamaan
        // bisa sama-sama lolos pemeriksaan ini, dan yang kalah memang harus gagal di sana.
        if (items.findByCode(command.tenantId, normalized) != null) {
            throw ConflictException("Kode item $normalized sudah dipakai")
        }
        return items.save(
            InventoryItem.create(
                command.tenantId, normalized, command.name, command.category, command.unit,
                command.serialized, command.trackMac, command.reorderPoint,
            ),
        )
    }

    /**
     * Hanya atribut deskriptif yang bisa diubah. `code`, `unit`, dan `serialized` SENGAJA
     * tidak — lihat [InventoryItem.describe]: mengubahnya membuat leg ledger lama tidak lagi
     * konsisten dengan flag barunya dan riwayat stok tidak bisa direkonstruksi.
     */
    @Transactional
    fun update(itemId: UUID, tenantId: UUID, command: UpdateInventoryItem): InventoryItem {
        val item = load(itemId, tenantId)
        item.describe(command.name, command.category, command.reorderPoint)
        item.trackMac(command.trackMac)
        return items.save(item)
    }

    /**
     * Item dinonaktifkan, BUKAN dihapus: ledger, saldo, dan aset serial lama tetap menunjuk
     * id ini. Menghapusnya akan membuat riwayat mutasi kehilangan nama barangnya dan laporan
     * selisih berisi baris tanpa identitas.
     */
    @Transactional
    fun setActive(itemId: UUID, tenantId: UUID, active: Boolean): InventoryItem {
        val item = load(itemId, tenantId)
        if (active) item.activate() else item.deactivate()
        return items.save(item)
    }

    @Transactional(readOnly = true)
    fun list(tenantId: UUID, includeInactive: Boolean = false): List<InventoryItem> =
        items.findAll(tenantId).filter { includeInactive || it.active }

    @Transactional(readOnly = true)
    fun get(itemId: UUID, tenantId: UUID): InventoryItem = load(itemId, tenantId)

    /**
     * Pemeriksaan tenant DIULANG di sini walau Hibernate `@TenantId` dan RLS sudah menyaring:
     * `findById` mencari lewat primary key, dan id yang ditebak dari tenant lain harus
     * berakhir sebagai "tidak ditemukan", bukan sebagai baris yang kebetulan lolos kalau
     * salah satu lapisan itu pernah dimatikan (mis. oleh worker yang lupa set tenant).
     */
    private fun load(itemId: UUID, tenantId: UUID): InventoryItem {
        val item = items.findById(itemId) ?: throw NotFoundException("Item gudang tidak ditemukan")
        if (item.tenantId != tenantId) throw NotFoundException("Item gudang tidak ditemukan")
        return item
    }

    @Transactional(readOnly = true)
    fun requireActive(itemId: UUID, tenantId: UUID): InventoryItem {
        val item = load(itemId, tenantId)
        if (!item.active) throw ValidationException("Item ${item.code} sudah dinonaktifkan")
        return item
    }
}

data class CreateInventoryItem(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val category: InventoryItemCategory,
    val unit: InventoryUnit,
    val serialized: Boolean,
    val trackMac: Boolean = false,
    val reorderPoint: BigDecimal? = null,
)

data class UpdateInventoryItem(
    val name: String,
    val category: InventoryItemCategory,
    val reorderPoint: BigDecimal? = null,
    val trackMac: Boolean = false,
)
