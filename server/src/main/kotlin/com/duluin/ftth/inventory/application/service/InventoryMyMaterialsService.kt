package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
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
class InventoryMyMaterialsService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val query: MyMaterialQuery,
    private val workbench: MaterialWorkbenchQuery, private val users: IamApi) : InventoryMyMaterialsApi {
    override fun jobs(page: WarehousePageRequest): WarehousePage<MyMaterialJob> {
        val current = current(null, page)
        return query.jobs(current.fence.identity.userId, page, access(current))
    }
    override fun authorize(context: MaterialPlanningContext) { current(context) }
    override fun custody(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialCustodyChoice> {
        val current = current(context, page)
        return workbench.custody(context.workOrderId, current.fence.identity.userId, page, access(current))
    }
    override fun issues(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MyMaterialIssue> {
        val current = current(context, page)
        return query.issues(context.workOrderId, current.fence.identity.userId, page, access(current))
    }
    override fun residuals(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MyMaterialResidual> {
        val current = current(context, page)
        val found = query.residuals(context.workOrderId, current.fence.identity.userId, page, access(current))
        val names = users.usersByIds(found.items.flatMap { listOfNotNull(it.sender?.id, it.receiver?.id) }.toSet()).associateBy { it.id }
        return found.copy(items = found.items.map { row -> row.copy(sender = row.sender?.id?.let { id -> names[id]?.let { WarehousePolicyChoice(id, it.name) } },
            receiver = row.receiver?.id?.let { id -> names[id]?.let { WarehousePolicyChoice(id, it.name) } }) })
    }
    override fun returnLocations(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<WarehouseApprovalLocation> {
        val current = current(context, page)
        return query.returnLocations(page, access(current))
    }
    private fun current(context: MaterialPlanningContext?, page: WarehousePageRequest? = null): CurrentAuthority {
        context?.cutover?.assertHeld(); context?.authority?.assertHeld()
        if (page != null && (page.page < 0 || page.size !in 1..100)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        receiptPermission(current, "workorder.order.field")
        if (context != null) {
            if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
            if (current.fence.identity.userId !in context.activeAssigneeIds && !query.belongs(context.workOrderId, current.fence.identity.userId)) masterFailure(WarehouseErrorCode.NOT_FOUND)
        }
        masters.lockTopology()
        return current
    }
    private fun access(current: CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }
}
