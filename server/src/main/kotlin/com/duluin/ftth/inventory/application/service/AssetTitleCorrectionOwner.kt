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
class AssetTitleCorrectionOwner(private val store: AssetTitleCorrectionStore, private val titles: AssetTitleStore,
    private val workOrders: AssetHandoverWorkOrderPort, private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore, private val jdbc: WarehouseCommandJdbc) : WarehouseApprovalOwner, WarehouseApprovalSourceLock {
    override val kind = "TITLE_CORRECTION"

    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val record = store.find(sourceDocumentId) ?: return
        workOrders.lockTitle(record.source.workOrderId, current.fence)
        titles.lockAssignment(record.source.assignmentId)
        titles.current(record.source.assignmentId)
    }

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val request = store.get(id)
        if (source.kind != kind || source.state != "DRAFT" || source.disposition != null || source.revision != 0L || source.requester != request.actorId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val request = store.get(attempt.sourceDocumentId)
        val guard = store.guard(record, attempt)
        val title = titles.current(request.source.assignmentId)
        if (title != request.source || titles.position(title.assetId) != request.position) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val request = store.get(record.snapshot.evaluation.sourceDocumentId)
        val source = request.position.dimension
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(request.id, 0, "POSTED", operation, MovementKind.TITLE_CORRECTION, request.reason,
            listOf(PostingLeg(LegDirection.OUT, source, quantity, request.id, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED),
                PostingLeg(LegDirection.IN, source.copy(legalOwner = request.targetOwner), quantity, request.id,
                    InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED)), approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        store.transfer(request, record.id, operation)
    }
}
