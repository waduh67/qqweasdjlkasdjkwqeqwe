package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class ReturnReacquisitionService(private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi,
    private val returns: WarehouseReturnStore, private val titles: ReturnReacquisitionStore,
    private val workOrders: AssetHandoverWorkOrderPort, private val masters: WarehouseMasterStore,
    private val access: WarehousePolicyAccess, private val clock: WarehousePolicyPersistence,
    private val scopes: InventoryWarehouseScopeApi, private val sites: com.duluin.ftth.network.SiteReferenceApi) : InventoryReturnReacquisitionApi {
    private val mapper = jacksonObjectMapper()

    override fun list(id: UUID, page: WarehousePageRequest): WarehousePage<ReturnReacquisitionEntry> {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        listOf("inventory.return.view", "inventory.approval.view").forEach { access.permission(current, it) }
        val returned = returns.get(id)
        // Read the original assignment's WO area even after the title effect has completed.
        workOrders.lockTitle(titles.context(id).workOrderId, current.fence)
        masters.lockTopology()
        listOfNotNull(returned.intake.quarantineLocationId, returned.view.locationId, returned.view.repair?.repairLocationId)
            .distinct().sortedBy(UUID::toString).forEach { access.location(it, current) }
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val visibility = WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
        return titles.list(id, page, visibility)
    }

    override fun request(id: UUID, input: ReturnReacquisitionInput, metadata: WarehouseMutationMetadata): ReturnReacquisitionRef {
        receiptKey(metadata.idempotencyKey)
        if (input.expectedRevision !in 0 until Long.MAX_VALUE ||
            listOf(input.reason, input.titleTransferReference).any { it.isBlank() || it.length > 500 })
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        listOf("inventory.return.manage", "inventory.approval.request", "inventory.approval.view").forEach { access.permission(current, it) }
        val context = titles.context(id)
        val workRevision = workOrders.lockTitle(context.workOrderId, current.fence)
        masters.lockTopology()
        val returned = returns.get(id, true)
        listOfNotNull(returned.intake.quarantineLocationId, returned.view.locationId, returned.view.repair?.repairLocationId).distinct().sortedBy(UUID::toString)
            .forEach { access.location(it, current) }
        val assetRevision = titles.lockAsset(returned.view.stockIdentityId)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "request" to input)))
        titles.replay(metadata.idempotencyKey, canonical.hash, current.fence.identity.userId)?.let { return it.reference() }
        val view = returned.view
        if (view.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (view.origin != WarehouseReturnOrigin.ASSET_REMOVAL || view.state != WarehouseReturnState.RECEIVED_IN_INSPECTION ||
            view.legalOwner != AssetLegalOwner.CUSTOMER || view.quantityBase != "1" ||
            !titles.canReacquire(returned)) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val source = returns.position(returned.source.dimension.copy(locationId = view.locationId, custodianId = view.locationId,
            custodianKind = OwnerKind.WAREHOUSE, condition = view.condition, legalOwner = view.legalOwner))
        if (source.tracking != WarehouseTracking.SERIAL || source.quantity != 1L) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val signature = workOrders.signature(context.workOrderId, input.evidenceId)
        val requestId = UUID.randomUUID()
        val record = ReturnTitleRecord(requestId, "RET-TITLE-$requestId", returned, context, source, assetRevision, input, signature,
            current.fence.identity.userId, workRevision, current.fence.epoch, cutover.snapshot.epoch, clock.now())
        titles.insert(record, metadata.idempotencyKey, canonical.hash)
        return record.reference()
    }
}
