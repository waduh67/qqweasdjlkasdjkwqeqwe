package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Component
class WarehouseTransferApprovalOwner(private val store: WarehouseTransferDiscrepancyStore,
    private val transfers: WarehouseTransferStore, private val stock: WarehouseTransferStock,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore,
    private val access: WarehouseTransferAccess) : WarehouseApprovalOwner, WarehouseApprovalSourceLock {
    override val kind = "ADJUSTMENT"
    private val mapper = jacksonObjectMapper()

    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val resolution = store.find(sourceDocumentId) ?: return
        val record = transfers.get(resolution.transferId)
        access.authorize(record, current)
        transfers.get(record.id, true)
    }

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val resolution = store.find(id) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (source.state != "DRAFT" || source.disposition != null || source.revision != 0L || source.requester != resolution.actorId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val transfer = transfers.get(resolution.transferId)
        if (transfer.state != WarehouseTransferState.DISCREPANCY || transfer.revision != resolution.transferRevision || transfer.resolutionDocumentId != id)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "This discrepancy report was superseded; use the current transfer report")
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val resolution = store.find(attempt.sourceDocumentId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val source = transfers.get(resolution.transferId)
        val guard = store.guard(record, attempt)
        if (source.revision != resolution.transferRevision || source.state != WarehouseTransferState.DISCREPANCY ||
            source.resolutionDocumentId != resolution.id) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        store.assertApproval(guard)
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val resolution = store.find(record.snapshot.evaluation.sourceDocumentId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val source = transfers.get(resolution.transferId)
        val lineIds = store.lines(resolution.id)
        val legs = source.lines.filter { it.received < it.quantity }.flatMap { line ->
            val expected = source.transitDimension(line.source.dimension).copy(stockIdentityId = requireNotNull(line.remainingIdentity))
            val position = stock.get(expected.stockIdentityId, source.binding.transitLocationId, dimension = expected)
            val dimension = position.dimension
            if (dimension.custodianId != source.id || dimension.custodianKind != OwnerKind.TRANSIT || position.status != InventoryStatus.IN_TRANSIT ||
                position.quantity != line.quantity - line.received || dimension.legalOwner != line.source.dimension.legalOwner)
                masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
            val lost = resolution.request.action == TransferRemainderAction.LOST
            val target = dimension.copy(locationId = resolution.request.destinationLocationId,
                custodianId = resolution.request.destinationLocationId, custodianKind = if (lost) OwnerKind.LOST else OwnerKind.WAREHOUSE)
            val quantity = StockQuantity.of(position.quantity, StockUnit.valueOf(position.unit.name))
            listOf(PostingLeg(LegDirection.OUT, dimension, quantity, lineIds.getValue(line.id), InventoryStatus.IN_TRANSIT),
                PostingLeg(LegDirection.IN, target, quantity, lineIds.getValue(line.id), if (lost) InventoryStatus.LOST else InventoryStatus.QUARANTINE))
        }
        posting.post(WarehousePost(resolution.id, 0, "POSTED", operation, MovementKind.TRANSFER, resolution.request.reason,
            legs, events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.RETURN_RECEIVED, operation.originalBody)), approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        val updated = source.copy(revision = source.revision + 1, recordedAt = operation.recordedAt,
            lines = source.lines.map { it.copy(resolved = it.quantity - it.received, remainingIdentity = null) })
        val receipt = PostingOperation(UUID.randomUUID(), "warehouse.transfer.resolve", record.id.toString(), current.fence.identity.userId,
            source.id, "transfer:${source.id}", record.snapshot.sourceHash, "RESOLVE", 200,
            mapper.writeValueAsString(updated.view()), current.fence.epoch, operation.recordedAt)
        transfers.advanceWithoutPosting(updated, receipt, cutover.snapshot.epoch, current.fence.identity.sessionId)
    }
}
