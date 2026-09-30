package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.service.WarehouseCompensationRecord
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseCompensationEffectStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun guard(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval = jdbc.execute { sql ->
        ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(mapper.writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")),
            ApprovalPostingKind.DISPOSITION_REVERSAL)
    }
    fun assertApproval(guard: ReceiptPostingApproval) = jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }

    fun begin(request: WarehouseCompensationRecord, approval: UUID, effect: PostingOperation): DispositionReturnTransition = jdbc.execute { sql ->
        val previous = request.returned.view
        val view = previous.copy(revision = Math.addExact(previous.revision, 1), state = WarehouseReturnState.RECEIVED_IN_INSPECTION,
            locationId = request.input.destinationLocationId, condition = WarehouseCondition.QUARANTINE, recordedAt = effect.recordedAt)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("returnId" to view.id, "requestId" to request.id,
            "approvalId" to approval, "sourceRevision" to previous.revision, "originalPostingId" to request.originalPostingId)))
        val operation = PostingOperation(UUID.randomUUID(), "warehouse.return.restore", approval.toString(), effect.actorId,
            view.id, "return:${view.id}", canonical.hash, "DISPOSITION_REVERSED", 200, mapper.writeValueAsString(view), effect.authorityEpoch, effect.recordedAt)
        sql.update("""INSERT INTO inventory_compensation_effect(tenant_id,request_id,approval_id,posting_operation_id,
            return_operation_id,return_id,source_return_revision,return_revision,original_posting_id) VALUES (?,?,?,?,?,?,?,?,?)""",
            sql.tenant, request.id, approval, effect.id, operation.id, view.id, previous.revision, view.revision, request.originalPostingId)
        DispositionReturnTransition(operation, canonical.json, view)
    }

    fun complete(request: WarehouseCompensationRecord, transition: DispositionReturnTransition) = jdbc.execute { sql ->
        val operation = transition.operation
        val view = transition.view
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
            document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key, operation.actorId,
            view.id, operation.resourceScope, operation.payloadHash, view.id, view.revision, operation.businessAction, 200,
            operation.originalBody, request.cutoverEpoch, operation.authorityEpoch, operation.recordedAt)
        if (sql.update("""UPDATE inventory_document SET state='RECEIVED_IN_INSPECTION',revision=revision+1,updated_at=?
            WHERE tenant_id=? AND id=? AND revision=? AND state IN ('LOST','SCRAP')""",
                operation.recordedAt, sql.tenant, view.id, request.returned.view.revision) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }
}
