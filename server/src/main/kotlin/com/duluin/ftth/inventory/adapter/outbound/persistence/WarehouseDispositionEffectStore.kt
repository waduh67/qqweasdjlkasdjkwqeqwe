package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.service.WarehouseDispositionRecord
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class DispositionReturnTransition(val operation: PostingOperation, val canonical: String, val view: WarehouseReturnView)

@Repository
class WarehouseDispositionEffectStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun guard(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, kind: ApprovalPostingKind): ReceiptPostingApproval = jdbc.execute { sql ->
        ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(mapper.writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")), kind)
    }

    fun assertApproval(guard: ReceiptPostingApproval) = jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }

    fun begin(request: WarehouseDispositionRecord, approval: UUID, effect: PostingOperation): DispositionReturnTransition = jdbc.execute { sql ->
        val previous = request.returned.view
        val lost = request.input.action == WarehouseDispositionAction.LOSS
        val view = previous.copy(revision = Math.addExact(previous.revision, 1),
            state = if (lost) WarehouseReturnState.LOST else WarehouseReturnState.SCRAP,
            locationId = request.input.destinationLocationId,
            condition = if (lost) previous.condition else WarehouseCondition.SCRAP, recordedAt = effect.recordedAt)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("returnId" to view.id,
            "requestId" to request.id, "approvalId" to approval, "sourceRevision" to previous.revision)))
        val operation = PostingOperation(UUID.randomUUID(), "warehouse.return.dispose", approval.toString(), effect.actorId,
            view.id, "return:${view.id}", canonical.hash, "DISPOSED", 200, mapper.writeValueAsString(view), effect.authorityEpoch, effect.recordedAt)
        sql.update("""INSERT INTO inventory_disposition_effect(tenant_id,request_id,approval_id,posting_operation_id,
            return_operation_id,return_id,source_return_revision,return_revision) VALUES (?,?,?,?,?,?,?,?)""",
            sql.tenant, request.id, approval, effect.id, operation.id, view.id, previous.revision, view.revision)
        DispositionReturnTransition(operation, canonical.json, view)
    }

    fun complete(request: WarehouseDispositionRecord, transition: DispositionReturnTransition) = jdbc.execute { sql ->
        val operation = transition.operation
        val view = transition.view
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
            document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key, operation.actorId,
            view.id, operation.resourceScope, operation.payloadHash, view.id, view.revision, operation.businessAction, 200,
            operation.originalBody, request.cutoverEpoch, operation.authorityEpoch, operation.recordedAt)
        if (sql.update("""UPDATE inventory_document SET state=?,revision=revision+1,updated_at=?
            WHERE tenant_id=? AND id=? AND revision=? AND state='RECEIVED_IN_INSPECTION'""",
                view.state, operation.recordedAt, sql.tenant, view.id, request.returned.view.revision) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }
}
