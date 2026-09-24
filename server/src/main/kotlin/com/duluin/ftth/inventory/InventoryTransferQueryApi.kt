package com.duluin.ftth.inventory

import java.util.UUID

/** Current display references accompany the operation view; stored replies remain immutable. */
interface InventoryTransferQueryApi {
    fun list(filter: WarehouseTransferFilter): WarehousePage<WarehouseTransferDetails>
    fun details(id: UUID): WarehouseTransferDetails
}

data class WarehouseTransferFilter(val page: Int = 0, val size: Int = 25,
    val state: WarehouseTransferState? = null, val locationId: UUID? = null, val query: String? = null)
data class WarehouseTransferDetails(val transfer: WarehouseTransferView, val references: WarehouseTransferReferences)
data class WarehouseTransferReferences(val locations: List<WarehouseTransferLocationRef>,
    val people: List<WarehouseTransferPersonRef>, val lines: List<WarehouseTransferLineRef>)
data class WarehouseTransferLocationRef(val id: UUID, val code: String, val name: String?)
data class WarehouseTransferPersonRef(val id: UUID, val name: String?)
data class WarehouseTransferLineRef(val lineId: UUID, val skuCode: String?, val skuName: String?,
    val serial: String?, val lotCode: String?)
