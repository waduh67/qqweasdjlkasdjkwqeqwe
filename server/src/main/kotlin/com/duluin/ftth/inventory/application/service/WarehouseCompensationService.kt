package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseCompensationService(private val cutovers: InventoryTenantCutoverApi,
    private val authorities: com.duluin.ftth.iam.CurrentAuthorityApi, private val access: WarehousePolicyAccess,
    private val workOrders: AssetHandoverWorkOrderPort, private val masters: WarehouseMasterStore,
    private val originals: WarehouseDispositionStore, private val returns: WarehouseReturnStore,
    private val store: WarehouseCompensationStore, private val clock: WarehousePolicyPersistence,
    private val scopes: InventoryWarehouseScopeApi, private val sites: com.duluin.ftth.network.SiteReferenceApi) : InventoryCompensationApi {
    private val mapper = jacksonObjectMapper()

    override fun request(dispositionId: UUID, input: WarehouseCompensationInput, metadata: WarehouseMutationMetadata): WarehouseCompensationView {
        receiptKey(metadata.idempotencyKey)
        if (input.expectedRevision !in 0 until Long.MAX_VALUE || input.expectedReturnRevision !in 0 until Long.MAX_VALUE ||
            input.reason.isBlank() || input.reason.length > 1000 || input.evidenceReference.isBlank() || input.evidenceReference.length > 500)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        listOf("inventory.custody.manage", "inventory.return.manage", "inventory.approval.request").forEach { access.permission(current, it) }
        val original = originals.get(dispositionId)
        val workRevision = workOrders.lockTitle(original.context.workOrderId, current.fence)
        masters.lockTopology()
        val returned = returns.get(original.returned.view.id, true)
        (locations(original) + returned.view.locationId + input.destinationLocationId).distinct().sortedBy(UUID::toString)
            .forEach { access.location(it, current) }
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("originalDispositionId" to dispositionId, "request" to input)))
        store.replay(metadata.idempotencyKey, canonical.hash, current.fence.identity.userId)?.let {
            authorize(it, current)
            if (it.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return it.view()
        }
        val prior = originals.view(dispositionId)
        if (prior.revision != input.expectedRevision || returned.view.revision != input.expectedReturnRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val lost = original.input.action == WarehouseDispositionAction.LOSS
        if (prior.state != WarehouseDispositionState.POSTED || prior.revision != 1L || returned.view.legalOwner != AssetLegalOwner.ISP ||
            returned.view.state != (if (lost) WarehouseReturnState.LOST else WarehouseReturnState.SCRAP))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val posting = store.originalPosting(original.id)
        if (posting.second != returned.view.revision || store.hasCompensation(posting.first)) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val target = access.location(input.destinationLocationId, current)
        if (target.kind != LocationKind.QUARANTINE || target.issueEligible) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val materialRevision = store.materialRevision(original.context.workOrderId)
        val assetRevision = originals.lockPhysical(original.input.stockIdentityId)
        val source = returns.position(original.source.dimension.copy(locationId = original.input.destinationLocationId,
            custodianId = original.input.destinationLocationId, custodianKind = if (lost) OwnerKind.LOST else OwnerKind.DISPOSED,
            condition = if (lost) original.source.dimension.condition else WarehouseCondition.SCRAP),
            if (lost) InventoryStatus.LOST else InventoryStatus.DISPOSED)
        if (source.quantity != original.source.quantity || source.unit != original.source.unit) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val id = UUID.randomUUID()
        val record = WarehouseCompensationRecord(id, "REV-$id", original, posting.first, input, current.fence.identity.userId,
            returned, source, WarehouseCompensationContext(original.context.workOrderId, workRevision, assetRevision, materialRevision),
            current.fence.epoch, cutover.snapshot.epoch, clock.now())
        store.insert(record, metadata.idempotencyKey, canonical)
        return record.view()
    }

    override fun get(dispositionId: UUID, id: UUID): WarehouseCompensationView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.custody.view")
        val record = store.get(id)
        if (record.original.id != dispositionId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        masters.lockTopology()
        authorize(record, current)
        return store.view(id)
    }

    override fun list(dispositionId: UUID, page: WarehousePageRequest): WarehousePage<WarehouseCompensationView> {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.custody.view")
        val original = originals.get(dispositionId)
        masters.lockTopology()
        locations(original).distinct().sortedBy(UUID::toString).forEach { access.location(it, current) }
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return store.list(dispositionId, page, WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false))
    }

    private fun locations(original: WarehouseDispositionRecord) = listOf(original.returned.intake.quarantineLocationId,
        original.source.dimension.locationId, original.input.destinationLocationId)
    private fun authorize(record: WarehouseCompensationRecord, current: CurrentAuthority) {
        (locations(record.original) + record.input.destinationLocationId).distinct().sortedBy(UUID::toString).forEach { access.location(it, current) }
    }
}
