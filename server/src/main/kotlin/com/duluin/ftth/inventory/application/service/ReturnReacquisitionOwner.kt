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
class ReturnReacquisitionOwner(private val titles: ReturnReacquisitionStore, private val returns: WarehouseReturnStore,
    private val workOrders: AssetHandoverWorkOrderPort, private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore, private val jdbc: WarehouseCommandJdbc) : WarehouseApprovalOwner, WarehouseApprovalSourceLock {
    override val kind = "RETURN_TITLE"

    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val request = titles.find(sourceDocumentId) ?: return
        workOrders.lockTitle(request.context.workOrderId, current.fence)
        returns.get(request.returned.view.id, true)
        titles.lockAsset(request.returned.view.stockIdentityId)
    }

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val request = titles.get(id)
        if (source.kind != kind || source.state != "DRAFT" || source.revision != 0L || source.disposition != null || source.requester != request.actorId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val request = titles.get(attempt.sourceDocumentId)
        val guard = titles.guard(record, attempt)
        val returned = returns.get(request.returned.view.id, true)
        if (returned.view != request.returned.view || titles.lockAsset(returned.view.stockIdentityId) != request.assetRevision ||
            workOrders.lockTitle(request.context.workOrderId, current.fence) != request.workOrderRevision ||
            returns.position(request.source.dimension) != request.source) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val request = titles.get(record.snapshot.evaluation.sourceDocumentId)
        val source = request.source.dimension
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(request.id, 0, "POSTED", operation, MovementKind.TITLE_CORRECTION, request.request.reason,
            listOf(PostingLeg(LegDirection.OUT, source, quantity, request.id, InventoryStatus.QUARANTINE),
                PostingLeg(LegDirection.IN, source.copy(legalOwner = AssetLegalOwner.ISP), quantity, request.id, InventoryStatus.QUARANTINE)),
            approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        val transition = titles.complete(request, record.id, operation)
        operations.storeIdentity(transition.first.id, transition.second, current.fence.identity.sessionId)
    }
}
