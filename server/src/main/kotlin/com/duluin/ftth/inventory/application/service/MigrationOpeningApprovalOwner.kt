package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.LocationSnapshot
import com.duluin.ftth.inventory.application.port.inbound.MasterKind
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MigrationOpeningApprovalSourceLock(private val openings: MigrationOpeningStore,
    private val access: WarehouseProvenanceAccess) : WarehouseApprovalSourceLock {
    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val opening = openings.find(sourceDocumentId)?.view ?: return
        openings.lockHistory(opening.batchId)
        access.sources(current)
    }
}

@Component
class MigrationOpeningApprovalOwner(private val openings: MigrationOpeningStore, private val admission: MigrationOpeningPostingStore,
    private val evidence: WarehouseOpeningBalanceService, private val masters: WarehouseMasterStore,
    private val guard: ReceiptApprovalPostingGuard, private val posting: WarehousePosting, private val operations: WarehouseOperationStore) : WarehouseApprovalOwner {
    override val kind = "OPENING_BALANCE"

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val opening = openings.find(id)?.view ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (source.state != "DRAFT" || source.revision != 0L || source.disposition != null)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Create a new sealed opening proposal from the current migration review")
        val review = openings.review(opening.batchId)
        val location = masters.get(MasterKind.LOCATION, opening.reviewLocation.id) as LocationSnapshot
        if (review.reviewHash != opening.reviewHash || review.issues.isNotEmpty() || location.revision != opening.reviewLocation.revision)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "The migration review or review location changed; create a new opening proposal")
        evidence.verifyEvidence(opening.manifest)
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val permit = admission.prepare(record, attempt)
        try { evidence.verifyEvidence(requireNotNull(openings.find(attempt.sourceDocumentId)).view.manifest) }
        catch (failure: WarehouseContractException) {
            if (failure.error.code !in setOf(WarehouseErrorCode.SOURCE_NOT_VERIFIED, WarehouseErrorCode.NOT_FOUND)) throw failure
            throw ApprovalPostingStopped(permit, WarehouseApprovalStatus.STALE)
        }
        return permit
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val source = record.snapshot.evaluation
        guard.beforeAdmission(approval)
        val legs = admission.admit(source.sourceDocumentId, record.id, operation.id)
        posting.post(WarehousePost(source.sourceDocumentId, source.sourceRevision, "POSTED", operation,
            MovementKind.OPENING_BALANCE, "Independently approved migration opening", legs, approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
    }
}
