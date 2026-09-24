package com.duluin.ftth.inventory

import java.util.UUID
import java.time.Instant

/** Current display references accompany the operation view; stored replies remain immutable. */
interface InventoryTransferQueryApi {
    fun list(filter: WarehouseTransferFilter): WarehousePage<WarehouseTransferDetails>
    fun details(id: UUID): WarehouseTransferDetails
    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseTransferView>
}

data class WarehouseTransferFilter(val page: Int = 0, val size: Int = 25,
    val state: WarehouseTransferState? = null, val locationId: UUID? = null, val query: String? = null,
    val skuId: UUID? = null, val serial: String? = null, val from: Instant? = null, val until: Instant? = null)
data class WarehouseTransferDetails(val transfer: WarehouseTransferView, val references: WarehouseTransferReferences)
data class WarehouseTransferReferences(val locations: List<WarehouseTransferLocationRef>,
    val people: List<WarehouseTransferPersonRef>, val lines: List<WarehouseTransferLineRef>)
data class WarehouseTransferLocationRef(val id: UUID, val code: String, val name: String?)
data class WarehouseTransferPersonRef(val id: UUID, val name: String?)
data class WarehouseTransferLineRef(val lineId: UUID, val skuCode: String?, val skuName: String?,
    val serial: String?, val lotCode: String?)
