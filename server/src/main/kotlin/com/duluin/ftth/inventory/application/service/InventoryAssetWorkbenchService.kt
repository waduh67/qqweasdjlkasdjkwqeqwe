package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.CustomerAssetWorkbenchQuery
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
class InventoryAssetWorkbenchService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi, private val query: CustomerAssetWorkbenchQuery) : InventoryAssetWorkbenchApi {
    override fun sources(context: MaterialPlanningContext, page: WarehousePageRequest, assetId: UUID?): WarehousePage<CustomerAssetSource> {
        context.cutover.assertHeld()
        val current = current(context.authority, page)
        receiptPermission(current, "customer.onu.assign"); receiptPermission(current, "workorder.order.field")
        if (current.fence.identity.userId !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.NOT_FOUND)
        masters.lockTopology()
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val access = WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
        return query.sources(context.workOrderId, current.fence.identity.userId, page, access, assetId)
    }
    override fun history(context: AssetCustomerReadContext, page: WarehousePageRequest, assignmentId: UUID?): WarehousePage<CustomerAssetAssignmentView> {
        context.cutover.assertHeld()
        receiptPermission(current(context.authority, page), "customer.onu.view")
        return query.history(context.customerId, page, assignmentId)
    }
    private fun current(fence: AuthorityFence, page: WarehousePageRequest): com.duluin.ftth.iam.CurrentAuthority {
        fence.assertHeld()
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        if (current.fence.identity != fence.identity || current.fence.epoch != fence.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        return current
    }
}
