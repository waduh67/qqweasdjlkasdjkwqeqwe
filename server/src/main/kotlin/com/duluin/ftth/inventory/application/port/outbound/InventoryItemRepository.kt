package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.InventoryItem
import java.util.UUID

/** Master data barang gudang (tabel `inventory_item`, V173). */
interface InventoryItemRepository {
    fun findById(id: UUID): InventoryItem?

    /**
     * Ambil banyak item sekaligus untuk MENAMAI satu daftar baris.
     *
     * Ada karena read model material WO dulu memanggil [findById] per baris: satu WO dengan 12
     * baris material membaca master barang 12 kali untuk membentuk satu respons. Bukan juga
     * [findAll], yang menyeret SELURUH katalog tenant demi belasan baris — pada tenant dengan
     * ribuan SKU ongkosnya justru lebih besar dari N+1 yang mau dihindari.
     *
     * Himpunan kosong TIDAK menyentuh basis data sama sekali. Baris yang idnya tak ditemukan
     * TIDAK dipulangkan, jadi pemanggil tetap harus memutuskan sendiri apa artinya item yang
     * hilang — di jalur baca itu memang kerusakan data yang pantas berbunyi.
     */
    fun findAllByIds(ids: Set<UUID>): List<InventoryItem>
    fun findByCode(tenantId: UUID, code: String): InventoryItem?
    fun findAll(tenantId: UUID): List<InventoryItem>
    fun save(item: InventoryItem): InventoryItem
}
