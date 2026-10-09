package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.InventoryWarehouseScopeApi
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehousePage
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceMovementStore
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class ReferenceMovementService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val store: ReferenceMovementStore) {
    private data class Scope(val locations: Set<UUID>, val costVisible: Boolean)

    fun list(kind: String?, page: Int, size: Int, search: String?): WarehousePage<ReferenceMovementSummary> {
        validatePage(page, size)
        if ((kind != null && kind !in setOf("RECEIPT", "TRANSFER")) || (search != null && search.length > 200))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val scope = scope()
        return store.list(scope.locations, scope.costVisible, kind, page, size, search)
    }

    fun get(id: UUID): ReferenceMovementSummary {
        val scope = scope()
        return store.get(id, scope.locations, scope.costVisible)
    }

    fun lines(id: UUID, page: Int, size: Int): WarehousePage<ReferenceMovementLineView> {
        validatePage(page, size)
        val scope = scope()
        return store.lines(store.get(id, scope.locations, scope.costVisible), page, size)
    }

    private fun scope(): Scope {
        val current = authority.lockCurrent()
        receiptPermission(current, "warehouse.stock.view")
        masters.lockTopology()
        val locations = mutableSetOf<UUID>()
        val warehouses = if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence)
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val visibleAreas = sites.visibleAreas(areas)
        var page = 0
        do {
            val batch = masters.list(MasterKind.LOCATION, MasterFilter(page = page, size = 100,
                locationKinds = setOf(LocationKind.WAREHOUSE, LocationKind.BIN)), warehouses, areas, visibleAreas)
            locations += batch.items.map { it.id }
            page++
        } while (page.toLong() * 100 < batch.totalElements)
        return Scope(locations, current.platformAdmin || "inventory.cost.view" in current.permissions)
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
