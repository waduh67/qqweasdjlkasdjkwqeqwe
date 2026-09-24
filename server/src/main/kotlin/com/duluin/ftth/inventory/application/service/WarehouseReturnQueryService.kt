package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReturnQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReturnSourceQuery
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseReturnQueryService(private val returns: InventoryReturnApi, private val query: WarehouseReturnQuery,
    private val sources: WarehouseReturnSourceQuery, private val cutovers: InventoryTenantCutoverApi,
    private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val users: IamApi) : InventoryReturnQueryApi {
    override fun list(filter: WarehouseReturnFilter): WarehousePage<WarehouseReturnDetails> {
        val page = returns.list(filter)
        val names = users.usersByIds(page.items.map { it.receivedBy }.toSet()).associate { it.id to it.name }
        return WarehousePage(page.items.map { WarehouseReturnDetails(it, query.references(it, names[it.receivedBy])) },
            page.page, page.size, page.totalElements)
    }

    override fun details(id: UUID): WarehouseReturnDetails {
        val view = returns.get(id)
        val name = users.usersByIds(setOf(view.receivedBy)).singleOrNull()?.name
        return WarehouseReturnDetails(view, query.references(view, name))
    }

    override fun sources(filter: WarehouseReturnFilter): WarehousePage<WarehouseReturnSourceOption> {
        validateReturnFilter(filter)
        if (filter.state != null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.manage")
        masters.lockTopology()
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val access = WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
        return sources.list(filter, access, current.fence.identity.userId)
    }

    override fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseReturnView> {
        validateReturnFilter(WarehouseReturnFilter(page.page, page.size))
        returns.get(id)
        return query.history(id, page)
    }
}

internal fun validateReturnFilter(filter: WarehouseReturnFilter) {
    if (filter.page < 0 || filter.size !in 1..100 || (filter.query != null && (filter.query.isBlank() || filter.query.length > 200)) ||
        (filter.from == null) != (filter.until == null) || (filter.from != null && filter.until != null &&
            (filter.from >= filter.until || Duration.between(filter.from, filter.until) > Duration.ofDays(366)))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
