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
class WarehouseDispositionAdmission(private val store: WarehouseDispositionStore, private val returns: WarehouseReturnStore,
    private val workOrders: AssetHandoverWorkOrderPort, private val masters: WarehouseMasterStore,
    private val access: WarehousePolicyAccess) : WarehouseApprovalSourceLock {
    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val request = store.find(sourceDocumentId) ?: return
        workOrders.lockTitle(request.context.workOrderId, current.fence)
        masters.lockTopology()
        listOf(request.returned.intake.quarantineLocationId, request.source.dimension.locationId, request.input.destinationLocationId)
            .distinct().sortedBy(UUID::toString).forEach { access.location(it, current) }
        returns.get(request.returned.view.id, true)
        store.lockPhysical(request.input.stockIdentityId, validate = false)
    }

    fun current(request: WarehouseDispositionRecord, current: CurrentAuthority): Boolean = try {
        returns.get(request.returned.view.id, true).view == request.returned.view &&
            store.lockPhysical(request.input.stockIdentityId) == request.context.assetRevision &&
            workOrders.lockTitle(request.context.workOrderId, current.fence) == request.context.workOrderRevision &&
            returns.position(request.source.dimension) == request.source
    } catch (failure: WarehouseContractException) {
        if (failure.error.code !in setOf(WarehouseErrorCode.SOURCE_NOT_VERIFIED, WarehouseErrorCode.INSUFFICIENT_STOCK)) throw failure
        false
    }
}

abstract class WarehouseDispositionOwner(override val kind: String, private val store: WarehouseDispositionStore,
    private val admission: WarehouseDispositionAdmission, private val effects: WarehouseDispositionEffectStore,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore) : WarehouseApprovalOwner {
    override fun validate(source: ApprovalSourceState, id: UUID) {
        val request = store.get(id)
        if (source.kind != kind || request.input.action.name != kind || source.state != "DRAFT" || source.revision != 0L ||
            source.disposition != null || source.requester != request.actorId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val request = store.get(attempt.sourceDocumentId)
        val guard = effects.guard(record, attempt, ApprovalPostingKind.valueOf(kind))
        if (!admission.current(request, current)) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        effects.assertApproval(guard)
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val request = store.get(record.snapshot.evaluation.sourceDocumentId)
        val transition = effects.begin(request, record.id, operation)
        val source = request.source.dimension
        val lost = request.input.action == WarehouseDispositionAction.LOSS
        val target = source.copy(locationId = request.input.destinationLocationId, custodianId = request.input.destinationLocationId,
            custodianKind = if (lost) OwnerKind.LOST else OwnerKind.DISPOSED,
            condition = if (lost) source.condition else WarehouseCondition.SCRAP)
        val quantity = StockQuantity.of(request.source.quantity, StockUnit.valueOf(request.source.unit.name))
        posting.post(WarehousePost(request.id, 0, "POSTED", operation, if (lost) MovementKind.LOSS else MovementKind.SCRAP,
            request.input.reason, listOf(PostingLeg(LegDirection.OUT, source, quantity, request.id, InventoryStatus.QUARANTINE),
                PostingLeg(LegDirection.IN, target, quantity, request.id, if (lost) InventoryStatus.LOST else InventoryStatus.DISPOSED)),
            events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.DISPOSED, operation.originalBody)), approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        effects.complete(request, transition)
        operations.storeIdentity(transition.operation.id, transition.canonical, current.fence.identity.sessionId)
    }
}

@Component
class WarehouseLossOwner(store: WarehouseDispositionStore, admission: WarehouseDispositionAdmission,
    effects: WarehouseDispositionEffectStore, posting: WarehousePosting, operations: WarehouseOperationStore) :
    WarehouseDispositionOwner("LOSS", store, admission, effects, posting, operations)

@Component
class WarehouseScrapOwner(store: WarehouseDispositionStore, admission: WarehouseDispositionAdmission,
    effects: WarehouseDispositionEffectStore, posting: WarehousePosting, operations: WarehouseOperationStore) :
    WarehouseDispositionOwner("SCRAP", store, admission, effects, posting, operations)
