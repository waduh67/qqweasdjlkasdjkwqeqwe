package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseAssetLossService(private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val workOrders: AssetHandoverWorkOrderPort,
    private val customers: AssetHandoverCustomerPort, private val masters: WarehouseMasterStore,
    private val titles: AssetTitleStore, private val store: WarehouseAssetLossStore, private val stock: WarehouseTransferStock,
    private val clock: WarehousePolicyPersistence, private val scopes: InventoryWarehouseScopeApi,
    private val sites: com.duluin.ftth.network.SiteReferenceApi) : InventoryAssetLossApi {
    private val mapper = jacksonObjectMapper()

    override fun request(input: WarehouseAssetLossInput, metadata: WarehouseMutationMetadata): WarehouseAssetLossView {
        receiptKey(metadata.idempotencyKey)
        if (listOf(input.expectedRevision, input.expectedTitleRevision, input.expectedWorkOrderRevision).any { it !in 0 until Long.MAX_VALUE } ||
            input.reason.isBlank() || input.reason.length > 1000) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        listOf("inventory.custody.manage", "inventory.approval.request").forEach { access.permission(current, it) }
        val handover = titles.handover(input.sourceHandoverId)
        if (handover.assignment.assignmentId != input.assignmentId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val revision = workOrders.lockTitle(handover.assignment.workOrderId, current.fence)
        masters.lockTopology()
        listOf(handover.position.dimension.locationId, input.destinationLocationId).distinct().sortedBy(UUID::toString)
            .forEach { access.location(it, current) }
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        store.replay(metadata.idempotencyKey, canonical.hash, current.fence.identity.userId)?.let {
            authorize(it, current)
            if (it.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            titles.lockAssignment(it.ownership.assignmentId)
            titles.lockAsset(it.ownership.assetId)
            customers.lockForException(it.ownership.customerId, it.ownership.assignmentId, current.fence)
            return it.view()
        }
        titles.lockAssignment(input.assignmentId)
        val ownership = titles.current(input.assignmentId)
        if (ownership.assignmentRevision != input.expectedRevision || ownership.titleRevision != input.expectedTitleRevision ||
            revision != input.expectedWorkOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (ownership.ownershipMode != AssetOwnershipMode.LOAN || ownership.legalOwner != AssetLegalOwner.ISP ||
            ownership.handoverId != input.sourceHandoverId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val position = titles.position(ownership.assetId)
        access.location(position.dimension.locationId, current)
        val target = access.location(input.destinationLocationId, current)
        if (target.kind != LocationKind.LOST || target.issueEligible) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        customers.lockForException(ownership.customerId, ownership.assignmentId, current.fence)
        val evidence = workOrders.signature(ownership.workOrderId, input.evidenceId)
        val source = stock.get(ownership.assetId, position.dimension.locationId, dimension = position.dimension)
        val id = UUID.randomUUID()
        val record = WarehouseAssetLossRecord(id, "LOST-$id", input, current.fence.identity.userId, ownership, position,
            source, evidence, revision, current.fence.epoch, cutover.snapshot.epoch, clock.now())
        store.insert(record, metadata.idempotencyKey, canonical)
        return record.view()
    }

    override fun get(id: UUID): WarehouseAssetLossView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.custody.view")
        val record = store.get(id)
        workOrders.lockTitle(record.ownership.workOrderId, current.fence)
        masters.lockTopology()
        authorize(record, current)
        return store.view(id)
    }

    override fun list(page: Int, size: Int): WarehousePage<WarehouseAssetLossView> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.custody.view")
        masters.lockTopology()
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return store.list(page, size, WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false))
    }

    private fun authorize(record: WarehouseAssetLossRecord, current: CurrentAuthority) {
        listOf(record.position.dimension.locationId, record.input.destinationLocationId).distinct().sortedBy(UUID::toString)
            .forEach { access.location(it, current) }
    }
}
