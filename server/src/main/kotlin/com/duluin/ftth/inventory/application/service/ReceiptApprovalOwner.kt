package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Component
import java.util.UUID

interface WarehouseApprovalOwner {
    val kind: String
    fun validate(source: ApprovalSourceState, id: UUID)
    fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval
    fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority, cutover: TenantCutoverFence, approval: ReceiptPostingApproval)
}

@Component
class ReceiptApprovalOwner(private val store: WarehouseReceiptPersistence, private val receipts: WarehouseReceiptService,
    private val scopes: InventoryWarehouseScopeApi, private val origins: WarehouseReceiptOrigins,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore, private val guard: ReceiptApprovalPostingGuard) : WarehouseApprovalOwner {
    override val kind = "RECEIPT"
    override fun validate(source: ApprovalSourceState, id: UUID) {
        if (source.state != "DRAFT" || source.disposition != null || store.get(id).revision != source.revision)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val receipt = store.get(record.snapshot.evaluation.sourceDocumentId, true)
        receipts.authorize(receipt.intake, current, scopes.currentUnderFence(current.fence))
        return guard.prepare(record, receipt, attempt)
    }
    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority, cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val source = record.snapshot.evaluation
        val receipt = store.get(source.sourceDocumentId, true)
        if (receipt.revision != source.sourceRevision || receipt.state != WarehouseReceiptState.DRAFT)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        receipts.authorize(receipt.intake, current, scopes.currentUnderFence(current.fence))
        guard.beforeAdmission(approval)
        val legs = origins.admit(receipt)
        posting.post(WarehousePost(receipt.id, receipt.revision, WarehouseReceiptState.RECEIVED_IN_INSPECTION.name,
            operation, MovementKind.RECEIVE, "Approved receipt ${receipt.intake.externalReference}", legs, approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
    }
}
