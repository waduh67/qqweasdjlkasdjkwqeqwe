package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseDispositionService(private val cutovers: InventoryTenantCutoverApi,
    private val authorities: com.duluin.ftth.iam.CurrentAuthorityApi, private val access: WarehousePolicyAccess,
    private val workOrders: AssetHandoverWorkOrderPort, private val masters: WarehouseMasterStore,
    private val returns: WarehouseReturnStore, private val store: WarehouseDispositionStore,
    private val stock: WarehouseTransferStock, private val clock: WarehousePolicyPersistence,
    private val scopes: InventoryWarehouseScopeApi, private val sites: com.duluin.ftth.network.SiteReferenceApi) : InventoryDispositionApi {
    private val mapper = jacksonObjectMapper()

    override fun request(input: WarehouseDispositionInput, metadata: WarehouseMutationMetadata): WarehouseDispositionView {
        receiptKey(metadata.idempotencyKey)
        val quantity = transferQuantity(input.quantityBase)
        if (input.expectedRevision !in 0 until Long.MAX_VALUE || input.reason.isBlank() || input.reason.length > 1000 ||
            input.evidenceReference.isBlank() || input.evidenceReference.length > 500) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        listOf("inventory.custody.manage", "inventory.return.manage", "inventory.approval.request").forEach { access.permission(current, it) }
        val workOrder = store.workOrder(input.sourceDocumentId)
        val workRevision = workOrders.lockTitle(workOrder, current.fence)
        masters.lockTopology()
        val returned = returns.get(input.sourceDocumentId, true)
        listOf(returned.intake.quarantineLocationId, returned.view.locationId, input.destinationLocationId).distinct()
            .sortedBy(UUID::toString).forEach { access.location(it, current) }
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        store.replay(metadata.idempotencyKey, canonical.hash, current.fence.identity.userId)?.let {
            authorize(it, current)
            if (it.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return it.view()
        }
        val view = returned.view
        if (view.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (view.state != WarehouseReturnState.RECEIVED_IN_INSPECTION || view.stockIdentityId != input.stockIdentityId ||
            view.legalOwner != AssetLegalOwner.ISP || view.quantityBase.toLong() != quantity || view.baseUnit != input.baseUnit ||
            input.action == WarehouseDispositionAction.SCRAP && view.condition != WarehouseCondition.DAMAGED)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val target = access.location(input.destinationLocationId, current)
        if (target.issueEligible || target.kind != if (input.action == WarehouseDispositionAction.LOSS) LocationKind.LOST else LocationKind.DISPOSED)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val dimension = returned.source.dimension.copy(locationId = view.locationId, custodianId = view.locationId,
            custodianKind = OwnerKind.WAREHOUSE, condition = view.condition, legalOwner = view.legalOwner)
        val assetRevision = store.lockPhysical(view.stockIdentityId)
        val source = returns.position(dimension)
        if (source.quantity != quantity || source.unit != input.baseUnit) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val value = stock.get(view.stockIdentityId, view.locationId, dimension = dimension)
        val id = UUID.randomUUID()
        val record = WarehouseDispositionRecord(id, "DSP-$id", input, current.fence.identity.userId, returned, source,
            value.cost, WarehouseDispositionContext(workOrder, workRevision, assetRevision), current.fence.epoch,
            cutover.snapshot.epoch, clock.now())
        store.insert(record, metadata.idempotencyKey, canonical)
        return record.view()
    }

    override fun get(id: UUID): WarehouseDispositionView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.custody.view")
        val record = store.get(id)
        masters.lockTopology()
        authorize(record, current)
        return store.view(id)
    }

    override fun list(filter: WarehouseDispositionFilter): WarehousePage<WarehouseDispositionView> {
        if (filter.page < 0 || filter.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.custody.view")
        masters.lockTopology()
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return store.list(filter, WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false))
    }

    private fun authorize(record: WarehouseDispositionRecord, current: CurrentAuthority) {
        listOf(record.returned.intake.quarantineLocationId, record.source.dimension.locationId, record.input.destinationLocationId)
            .distinct().sortedBy(UUID::toString).forEach { access.location(it, current) }
    }
}
