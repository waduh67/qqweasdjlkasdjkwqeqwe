package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceStockStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceWarehouseStore
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.util.UUID

@Service
@Transactional
class ReferenceStockService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi,
    private val store: ReferenceStockStore, private val history: ReferenceWarehouseStore) {
    private data class Scope(val current: CurrentAuthority, val sku: SkuSnapshot, val locations: List<LocationSnapshot>) {
        val ids = locations.map { it.id }.toSet()
        val holder = if (current.platformAdmin || "warehouse.request.handover" in current.permissions) null else current.fence.identity.userId
    }

    fun stock(skuId: UUID, includePositions: Boolean): ReferenceSkuStock {
        val scope = scope(skuId)
        val quantities = store.quantities(skuId, scope.ids)
        val byId = scope.locations.associateBy { it.id }
        val warehouses = scope.locations.filter { it.kind == LocationKind.WAREHOUSE }.map { warehouse ->
            val quantity = scope.locations.filter { belongs(it, warehouse.id, byId) }
                .fold(BigInteger.ZERO) { total, location -> total + (quantities[location.id] ?: BigInteger.ZERO) }
            ReferenceWarehouseQuantity(warehouse.id, warehouse.name ?: warehouse.code, quantity.toString())
        }
        val positions = if (includePositions) history.positions(skuId).filter { it.locationId in scope.ids &&
            (scope.holder == null || it.holderKind !in setOf("TECHNICIAN", "VEHICLE") || it.holderId == scope.holder) } else emptyList()
        return ReferenceSkuStock(scope.sku, warehouses, positions)
    }

    fun positions(skuId: UUID, page: Int, size: Int, search: String?, locationId: UUID?,
        holderKind: String?, availableOnly: Boolean): WarehousePage<ReferenceStockPosition> {
        validatePage(page, size)
        if ((search != null && search.length > 200) || (holderKind != null && holderKind !in setOf("WAREHOUSE", "TECHNICIAN", "VEHICLE", "CUSTOMER")))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val scope = scope(skuId)
        if (locationId != null && locationId !in scope.ids) masterFailure(WarehouseErrorCode.NOT_FOUND)
        return store.positions(skuId, locationId?.let { setOf(it) } ?: scope.ids, scope.holder, page, size, search, holderKind, availableOnly)
    }

    fun history(skuId: UUID, page: Int, size: Int): WarehousePage<ReferenceStockHistory> {
        validatePage(page, size)
        val scope = scope(skuId)
        return history.history(skuId, scope.ids, scope.holder, page, size)
    }

    private fun scope(skuId: UUID): Scope {
        val current = authority.lockCurrent()
        receiptPermission(current, "warehouse.stock.view")
        masters.lockTopology()
        val sku = masters.get(MasterKind.SKU, skuId) as SkuSnapshot
        val locations = mutableListOf<LocationSnapshot>()
        val scope = if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence)
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val visibleAreas = sites.visibleAreas(areas)
        var page = 0
        do {
            val batch = masters.list(MasterKind.LOCATION, MasterFilter(page = page, size = 100, state = WarehouseMasterState.ACTIVE), scope, areas, visibleAreas)
            locations += batch.items.filterIsInstance<LocationSnapshot>()
            page++
        } while (page.toLong() * 100 < batch.totalElements)
        return Scope(current, sku, locations)
    }

    private fun belongs(location: LocationSnapshot, warehouse: UUID, byId: Map<UUID, LocationSnapshot>): Boolean {
        var parent: LocationSnapshot? = location
        val visited = mutableSetOf<UUID>()
        while (parent != null && visited.add(parent.id) && visited.size <= 32) {
            if (parent.id == warehouse) return true
            parent = parent.parentLocationId?.let(byId::get)
        }
        return false
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
