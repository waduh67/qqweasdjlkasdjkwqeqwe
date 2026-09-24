package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventoryMaterialWorkbenchService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val plans: MaterialPlanningStore,
    private val physical: MaterialPhysicalTotalsStore, private val query: MaterialWorkbenchQuery,
    private val reworks: MaterialReworkStore, private val users: IamApi) : InventoryMaterialWorkbenchApi {
    override fun context(context: MaterialPlanningContext): MaterialFieldContext {
        current(context)
        val history = plans.current(context.workOrderId)
        val latest = query.latestUsage(context.workOrderId)
        val rework = history?.plan?.id?.let(reworks::get)
        return MaterialFieldContext(context.workOrderId, context.workOrderRevision, history?.plan, history?.state,
            physical.useRevision(context.workOrderId), latest,
            rework?.reworkId, rework?.evidenceRevision)
    }

    override fun custody(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialCustodyChoice> {
        val current = current(context, page)
        receiptPermission(current, "workorder.order.field")
        return query.custody(context.workOrderId, current.fence.identity.userId, page, access(current))
    }

    override fun usage(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialUsageView> {
        val current = current(context, page)
        val rows = query.usage(context.workOrderId, if (current.platformAdmin || "workorder.order.view" in current.permissions) null else current.fence.identity.userId, page, access(current))
        val names = users.usersByIds(rows.items.mapNotNull { it.actor?.id }.toSet()).associateBy { it.id }
        return rows.copy(items = rows.items.map { row -> row.copy(actor = row.actor?.id?.let { id -> names[id]?.let { WarehousePolicyChoice(id, it.name) } }) })
    }

    private fun current(context: MaterialPlanningContext, page: WarehousePageRequest? = null): com.duluin.ftth.iam.CurrentAuthority {
        context.cutover.assertHeld(); context.authority.assertHeld()
        if (page != null && (page.page < 0 || page.size !in 1..100)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        if (!current.platformAdmin && "workorder.order.view" !in current.permissions &&
            !("workorder.order.field" in current.permissions && current.fence.identity.userId in context.activeAssigneeIds)) masterFailure(WarehouseErrorCode.FORBIDDEN)
        masters.lockTopology()
        return current
    }
    private fun access(current: com.duluin.ftth.iam.CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }
}
