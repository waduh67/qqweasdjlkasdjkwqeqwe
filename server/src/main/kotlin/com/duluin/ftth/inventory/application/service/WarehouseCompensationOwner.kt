package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class WarehouseCompensationAdmission(private val store: WarehouseCompensationStore, private val originals: WarehouseDispositionStore,
    private val returns: WarehouseReturnStore, private val workOrders: AssetHandoverWorkOrderPort,
    private val masters: WarehouseMasterStore, private val access: WarehousePolicyAccess) : WarehouseApprovalSourceLock {
    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val request = store.find(sourceDocumentId) ?: return
        workOrders.lockTitle(request.context.workOrderId, current.fence)
        masters.lockTopology()
        listOf(request.original.returned.intake.quarantineLocationId, request.original.source.dimension.locationId,
            request.source.dimension.locationId, request.input.destinationLocationId).distinct().sortedBy(UUID::toString)
            .forEach { access.location(it, current) }
        returns.get(request.returned.view.id, true)
        originals.lockPhysical(request.source.dimension.stockIdentityId, validate = false)
    }

    fun current(request: WarehouseCompensationRecord, current: CurrentAuthority): Boolean = try {
        val destination = access.location(request.input.destinationLocationId, current)
        destination.kind == LocationKind.QUARANTINE && !destination.issueEligible &&
            returns.get(request.returned.view.id, true).view == request.returned.view &&
            originals.view(request.original.id).let { it.state == WarehouseDispositionState.POSTED && it.revision == 1L } &&
            !store.hasCompensation(request.originalPostingId) &&
            store.materialRevision(request.context.workOrderId) == request.context.materialRevision &&
            workOrders.lockTitle(request.context.workOrderId, current.fence) == request.context.workOrderRevision &&
            originals.lockPhysical(request.source.dimension.stockIdentityId) == request.context.assetRevision &&
            returns.position(request.source.dimension, if (request.original.input.action == WarehouseDispositionAction.LOSS)
                InventoryStatus.LOST else InventoryStatus.DISPOSED) == request.source
    } catch (failure: WarehouseContractException) {
        if (failure.error.code !in setOf(WarehouseErrorCode.SOURCE_NOT_VERIFIED, WarehouseErrorCode.INSUFFICIENT_STOCK)) throw failure
        false
    }
}

@Component
class WarehouseCompensationOwner(private val store: WarehouseCompensationStore, private val admission: WarehouseCompensationAdmission,
    private val effects: WarehouseCompensationEffectStore, private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore) : WarehouseApprovalOwner {
    override val kind = "DISPOSITION_REVERSAL"

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val request = store.get(id)
        if (source.kind != kind || source.state != "DRAFT" || source.revision != 0L || source.disposition != null || source.requester != request.actorId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val guard = effects.guard(record, attempt)
        if (!admission.current(store.get(attempt.sourceDocumentId), current)) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        effects.assertApproval(guard)
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val request = store.get(record.snapshot.evaluation.sourceDocumentId)
        val transition = effects.begin(request, record.id, operation)
        val source = request.source.dimension
        val target = source.copy(locationId = request.input.destinationLocationId, custodianId = request.input.destinationLocationId,
            custodianKind = OwnerKind.WAREHOUSE, condition = WarehouseCondition.QUARANTINE)
        val quantity = StockQuantity.of(request.source.quantity, StockUnit.valueOf(request.source.unit.name))
        val status = if (request.original.input.action == WarehouseDispositionAction.LOSS) InventoryStatus.LOST else InventoryStatus.DISPOSED
        posting.post(WarehousePost(request.id, 0, "POSTED", operation, MovementKind.REVERSAL, request.input.reason,
            listOf(PostingLeg(LegDirection.OUT, source, quantity, request.id, status),
                PostingLeg(LegDirection.IN, target, quantity, request.id, InventoryStatus.QUARANTINE)),
            events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.DISPOSITION_REVERSED, operation.originalBody)),
            compensatesPostingId = request.originalPostingId, approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        effects.complete(request, transition)
        operations.storeIdentity(transition.operation.id, transition.canonical, current.fence.identity.sessionId)
    }
}
