package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialHandoverWorkbenchQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventoryMaterialHandoverWorkbenchService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val query: MaterialHandoverWorkbenchQuery,
    private val users: IamApi) : InventoryMaterialHandoverWorkbenchApi {
    override fun sources(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialHandoverSource> {
        val current = current(context, page, true)
        val rows = query.sources(context, page, access(current))
        val names = names(rows.items.map { it.sender.id }.toSet())
        return rows.copy(items = rows.items.map { row -> row.copy(sender = row.sender.copy(name = names[row.sender.id] ?: "Pemegang material")) })
    }
    override fun targets(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialHandoverTarget> {
        val current = current(context, page, true)
        val rows = query.targets(context, page, access(current))
        val names = names(rows.items.map { it.receiver.id }.toSet())
        return rows.copy(items = rows.items.map { row -> row.copy(receiver = row.receiver.copy(name = names[row.receiver.id] ?: "Teknisi penerima")) })
    }
    override fun pending(context: MaterialPlanningContext, page: WarehousePageRequest, id: UUID?): WarehousePage<MaterialHandoverGrant> {
        val current = current(context, page, false)
        val rows = query.pending(context, page, access(current), id)
        val names = names(rows.items.flatMap { listOf(it.sender.id, it.receiver.id, it.dispatcher.id) }.toSet())
        return rows.copy(items = rows.items.map { row -> row.copy(sender = row.sender.copy(name = names[row.sender.id] ?: "Pemegang material"),
            receiver = row.receiver.copy(name = names[row.receiver.id] ?: "Teknisi penerima"), dispatcher = row.dispatcher.copy(name = names[row.dispatcher.id] ?: "Dispatcher")) })
    }
    private fun names(ids: Set<UUID>) = users.usersByIds(ids).associate { it.id to it.name }
    private fun current(context: MaterialPlanningContext, page: WarehousePageRequest, dispatcher: Boolean): CurrentAuthority {
        context.authority.assertHeld(); context.cutover.assertHeld()
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        if (dispatcher) { receiptPermission(current, "workorder.order.assign"); receiptPermission(current, "inventory.issue.manage") }
        else receiptPermission(current, "workorder.order.field")
        masters.lockTopology()
        return current
    }
    private fun access(current: CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }
}
