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
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventoryMyMaterialsService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val query: MyMaterialQuery,
    private val workbench: MaterialWorkbenchQuery, private val users: IamApi, private val rma: InventoryCustomerRmaApi) : InventoryMyMaterialsApi {
    override fun jobs(page: WarehousePageRequest): WarehousePage<MyMaterialJob> {
        val current = current(null, page)
        return query.jobs(current.fence.identity.userId, page, access(current))
    }
    override fun authorize(context: MaterialPlanningContext) { current(context) }
    override fun custody(context: MaterialPlanningContext, page: WarehousePageRequest, identity: UUID?): WarehousePage<MaterialCustodyChoice> {
        val current = current(context, page)
        return workbench.custody(context.workOrderId, current.fence.identity.userId, page, access(current), identity)
    }
    override fun issues(context: MaterialPlanningContext, page: WarehousePageRequest, issue: UUID?): WarehousePage<MyMaterialIssue> {
        val current = current(context, page)
        return query.issues(context.workOrderId, current.fence.identity.userId, page, access(current), issue)
    }
    override fun residuals(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MyMaterialResidual> {
        val current = current(context, page)
        val found = query.residuals(context.workOrderId, current.fence.identity.userId, page, access(current))
        return names(found)
    }
    override fun rmas(context: MaterialPlanningContext, page: WarehousePageRequest, handover: UUID?): WarehousePage<CustomerRmaHandoverDetails> {
        val current = current(context, page)
        val rows = query.rmas(context.workOrderId, current.fence.identity.userId, page, access(current), handover)
        return WarehousePage(rows.items.map { rma.details(it.id) }, rows.page, rows.size, rows.totalElements)
    }
    override fun pendingReturns(page: WarehousePageRequest): WarehousePage<MyMaterialResidual> {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.view")
        masters.lockTopology()
        return names(query.residuals(null, current.fence.identity.userId, page, access(current), true))
    }
    private fun names(found: WarehousePage<MyMaterialResidual>): WarehousePage<MyMaterialResidual> {
        val names = users.usersByIds(found.items.flatMap { listOfNotNull(it.sender?.id, it.receiver?.id) }.toSet()).associateBy { it.id }
        return found.copy(items = found.items.map { row -> row.copy(sender = row.sender?.id?.let { id -> names[id]?.let { WarehousePolicyChoice(id, it.name) } },
            receiver = row.receiver?.id?.let { id -> names[id]?.let { WarehousePolicyChoice(id, it.name) } }) })
    }
    override fun returnLocations(context: MaterialPlanningContext, page: WarehousePageRequest, location: UUID?): WarehousePage<WarehouseApprovalLocation> {
        val current = current(context, page)
        return query.returnLocations(page, access(current), location)
    }
    private fun current(context: MaterialPlanningContext?, page: WarehousePageRequest? = null): CurrentAuthority {
        context?.cutover?.assertHeld(); context?.authority?.assertHeld()
        if (page != null && (page.page < 0 || page.size !in 1..100)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        receiptPermission(current, "workorder.order.field")
        masters.lockTopology()
        if (context != null) {
            if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
            if (current.fence.identity.userId !in context.activeAssigneeIds && !query.belongs(context.workOrderId, current.fence.identity.userId, access(current))) masterFailure(WarehouseErrorCode.NOT_FOUND)
        }
        return current
    }
    private fun access(current: CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }
}
