package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceStockStore
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.util.UUID

@Service
@Transactional
class ReferenceRequestStockService(private val requests: ReferenceRequestService, private val masters: WarehouseMasterStore,
    private val stock: ReferenceStockStore, private val iam: IamApi) {
    fun preview(skuId: UUID, technicianId: UUID?, warehouseId: UUID?, requestId: UUID?, page: Int, size: Int): ReferenceRequestStockPreview {
        if (page < 0 || size !in 1..100 || technicianId != null && warehouseId != null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val access = requests.readAccess()
        val managing = access.current.platformAdmin || "warehouse.request.review" in access.current.permissions
        val request = requestId?.let { requests.detail(it).request }
        if (request == null && !managing) requests.permission(access.current, "warehouse.request.own")
        if (request != null && request.lines.none { it.skuId == skuId } && !(managing && request.state == ReferenceRequestState.SUBMITTED))
            masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (request != null && (technicianId != null && technicianId != request.technicianId || warehouseId != null && warehouseId != request.warehouseId))
            masterFailure(WarehouseErrorCode.NOT_FOUND)
        val destinationWarehouse = request?.warehouseId ?: warehouseId
        val destinationTechnician = if (destinationWarehouse != null) null else request?.technicianId ?: technicianId ?: access.current.fence.identity.userId
        if (request == null && !managing && (destinationWarehouse != null || destinationTechnician != access.current.fence.identity.userId))
            masterFailure(WarehouseErrorCode.FORBIDDEN)
        destinationWarehouse?.let { requests.warehouse(it, access) }
        val technician = destinationTechnician?.let { iam.findUser(it)?.takeIf { user -> user.active && user.technician }
            ?: masterFailure(WarehouseErrorCode.NOT_FOUND) }
        val sku = masters.get(MasterKind.SKU, skuId) as SkuSnapshot
        if (sku.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.NOT_FOUND)
        val locations = requests.visibleWarehouses(access).map { masters.get(MasterKind.LOCATION, it) as LocationSnapshot }
        val quantities = stock.quantities(skuId, locations.map { it.id }.toSet())
        val byId = locations.associateBy { it.id }
        fun root(location: LocationSnapshot): UUID? {
            var current: LocationSnapshot? = location
            val visited = mutableSetOf<UUID>()
            while (current != null && visited.add(current.id) && visited.size <= 32) {
                if (current.id == destinationWarehouse || current.kind == LocationKind.WAREHOUSE) return current.id
                current = current.parentLocationId?.let(byId::get)
            }
            return null
        }
        val totals = locations.groupBy(::root).mapValues { (_, members) -> members.fold(BigInteger.ZERO) { sum, location -> sum + (quantities[location.id] ?: BigInteger.ZERO) } }
        val warehouses = locations.filter { if (destinationWarehouse == null) it.kind == LocationKind.WAREHOUSE else it.id == destinationWarehouse }
            .sortedWith(compareBy<LocationSnapshot> { (it.name ?: it.code).lowercase() }.thenBy { it.id })
            .map { ReferenceWarehouseQuantity(it.id, it.name ?: it.code, (totals[it.id] ?: BigInteger.ZERO).toString()) }
        return ReferenceRequestStockPreview(sku.id, sku.name, sku.baseUnit, warehouses.fold(BigInteger.ZERO) { sum, row -> sum + row.quantityBase.toBigInteger() }.toString(),
            technician?.id, technician?.name, technician?.let { stock.technicianQuantity(skuId, it.id).toString() },
            WarehousePage(warehouses.drop((page.toLong() * size).coerceAtMost(warehouses.size.toLong()).toInt()).take(size), page, size, warehouses.size.toLong()))
    }
}
