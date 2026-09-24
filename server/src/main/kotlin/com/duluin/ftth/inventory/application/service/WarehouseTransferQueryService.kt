package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseTransferQuery
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseTransferQueryService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi,
    private val users: IamApi, private val query: WarehouseTransferQuery, private val transfers: InventoryTransferApi) : InventoryTransferQueryApi {
    override fun list(filter: WarehouseTransferFilter): WarehousePage<WarehouseTransferDetails> {
        if (filter.page < 0 || filter.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (filter.query != null && (filter.query.isBlank() || filter.query.length > 200)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.transfer.view")
        masters.lockTopology()
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val access = WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
        val receivers = users.usersByIds(query.receiverIds(access)).filter { it.active }.associate { it.id to it.technician }
        val page = query.list(filter, access, receivers)
        val names = users.usersByIds(page.items.flatMap { listOf(it.senderId,it.receiverId) }.toSet()).associate { it.id to it.name }
        return WarehousePage(page.items.map { details(it, names) }, page.page, page.size, page.totalElements)
    }

    override fun details(id: UUID): WarehouseTransferDetails {
        val view = transfers.get(id)
        val names = users.usersByIds(setOf(view.senderId, view.receiverId)).associate { it.id to it.name }
        return details(view, names)
    }

    override fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseTransferView> {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        transfers.get(id)
        return query.history(id, page)
    }

    private fun details(view: WarehouseTransferView, names: Map<UUID, String>) = WarehouseTransferDetails(view,
        WarehouseTransferReferences(query.locationReferences(view), listOf(view.senderId,view.receiverId).distinct().map {
            WarehouseTransferPersonRef(it, names[it])
        }, query.lineReferences(view)))
}
