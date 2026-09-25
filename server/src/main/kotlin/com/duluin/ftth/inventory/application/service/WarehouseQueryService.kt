package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryPersistence
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseAssetQueries
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseLotQueries
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseShortageQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseLegacyQueries
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WarehouseQueryService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val sites: SiteReferenceApi, private val store: WarehouseQueryPersistence,
    private val assets: WarehouseAssetQueries, private val lots: WarehouseLotQueries, private val shortages: WarehouseShortageQuery,
    private val legacy: WarehouseLegacyQueries) {
    @Transactional(timeout = 20)
    fun legacyStock(): String = store.legacyStock(access())

    @Transactional(timeout = 20)
    fun legacyLocations(): String = legacy.locations(access("inventory.location.view"))

    @Transactional(timeout = 20)
    fun legacyItems(): String = legacy.items(access())

    @Transactional(timeout = 20)
    fun legacyCustody(): String = legacy.custody(access("inventory.custody.view"))

    @Transactional(timeout = 20)
    fun legacyReservations(): String = legacy.reservations(access("inventory.custody.view"))

    @Transactional(timeout = 20)
    fun legacyAsset(id: UUID): String = legacy.asset(access(), id)

    @Transactional(timeout = 20)
    fun stockHistory(parameters: Map<String, List<String>>, id: UUID): String = store.history(WarehouseQueryFilter.parse(parameters, true), access(), id)

    @Transactional(timeout = 20)
    fun assets(parameters: Map<String, List<String>>, id: UUID? = null, history: Boolean = false): String =
        assets.assets(WarehouseQueryFilter.parse(parameters, history), access(), id, history)

    @Transactional(timeout = 20)
    fun lots(parameters: Map<String, List<String>>, id: UUID? = null, part: String? = null, segmentId: UUID? = null): String =
        lots.lots(WarehouseQueryFilter.parse(parameters, part == "history"), access(), id, part, segmentId)

    @Transactional(timeout = 20)
    fun stock(parameters: Map<String, List<String>>): String = store.stock(WarehouseQueryFilter.parse(parameters, allowBucket = true), access())

    @Transactional(timeout = 20)
    fun shortages(parameters: Map<String, List<String>>): String {
        if (parameters.keys.any { it !in setOf("page", "size", "skuId", "locationId") }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return shortages.list(WarehouseQueryFilter.parse(parameters), access())
    }

    @Transactional(timeout = 20)
    fun positions(parameters: Map<String, List<String>>, id: UUID? = null): String = store.positions(WarehouseQueryFilter.parse(parameters, allowBucket = true), access(), id)

    @Transactional(timeout = 20)
    fun unknown(parameters: Map<String, List<String>>): String {
        val access = access()
        if (!access.provenance) masterFailure(WarehouseErrorCode.FORBIDDEN)
        return store.unknown(WarehouseQueryFilter.parse(parameters), access)
    }

    private fun access(permission: String = "inventory.item.view"): WarehouseQueryAccess {
        val current = authority.lockCurrent()
        receiptPermission(current, permission)
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence),
            areas, sites.visibleAreas(areas), current.platformAdmin || "inventory.cost.view" in current.permissions,
            current.platformAdmin || "inventory.provenance.view" in current.permissions)
    }
}
