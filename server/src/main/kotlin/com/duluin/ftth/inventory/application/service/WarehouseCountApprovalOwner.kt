package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class WarehouseCountApprovalOwner(private val counts: WarehouseCountStore, private val variances: WarehouseCountVarianceStore,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore, private val jdbc: WarehouseCommandJdbc) : WarehouseApprovalOwner {
    override val kind = "COUNT"

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val session = counts.get(id)
        if (source.state != "SUBMITTED" || session.view.state != WarehouseCountState.SUBMITTED || counts.unchanged(session))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Submit a measured count variance before requesting independent approval")
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val session = counts.get(attempt.sourceDocumentId)
        val guard = variances.guard(record, attempt)
        variances.lock(session)
        if (counts.stale(session)) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        variances.legs(session)
        jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val session = counts.get(record.snapshot.evaluation.sourceDocumentId)
        if (counts.stale(session)) throw ApprovalPostingStopped(approval, WarehouseApprovalStatus.STALE)
        val legs = variances.legs(session)
        counts.advance(session.view.id, session.view.revision, WarehouseCountState.APPROVED)
        posting.post(WarehousePost(session.view.id, session.view.revision + 1, "POSTED", operation, MovementKind.COUNT_VARIANCE,
            "Independently approved physical count variance", legs, approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        counts.complete(session, session.view.revision, operation.id, record.id)
    }
}
