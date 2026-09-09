package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryPersistence
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WarehouseQueryService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val sites: SiteReferenceApi, private val store: WarehouseQueryPersistence) {
    @Transactional(timeout = 20)
    fun stock(parameters: Map<String, List<String>>): String = store.stock(WarehouseQueryFilter.parse(parameters), access())

    @Transactional(timeout = 20)
    fun positions(parameters: Map<String, List<String>>, id: UUID? = null): String = store.positions(WarehouseQueryFilter.parse(parameters), access(), id)

    @Transactional(timeout = 20)
    fun unknown(parameters: Map<String, List<String>>): String {
        val access = access()
        if (!access.provenance) masterFailure(WarehouseErrorCode.FORBIDDEN)
        return store.unknown(WarehouseQueryFilter.parse(parameters), access)
    }

    private fun access(): WarehouseQueryAccess {
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.item.view")
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence),
            areas, sites.visibleAreas(areas), current.platformAdmin || "inventory.cost.view" in current.permissions,
            current.platformAdmin || "inventory.provenance.view" in current.permissions)
    }
}
