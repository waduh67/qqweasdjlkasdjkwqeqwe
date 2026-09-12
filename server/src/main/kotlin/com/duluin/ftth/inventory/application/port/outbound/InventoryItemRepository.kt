package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.InventoryItem
import java.util.UUID

/** Master data barang gudang (tabel `inventory_item`, V173). */
interface InventoryItemRepository {
    fun findById(id: UUID): InventoryItem?
    fun findByCode(tenantId: UUID, code: String): InventoryItem?
    fun findAll(tenantId: UUID): List<InventoryItem>
    fun save(item: InventoryItem): InventoryItem
}
