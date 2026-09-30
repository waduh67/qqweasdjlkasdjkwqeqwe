package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.AssetTitleCorrectionSnapshot
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class AssetTitleCorrectionStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun find(id: UUID): AssetTitleCorrectionSnapshot? = jdbc.execute { sql ->
        if (sql.value("SELECT kind FROM inventory_document WHERE tenant_id=? AND id=?", sql.tenant, id) != "TITLE_CORRECTION") return@execute null
        sql.value("SELECT snapshot FROM inventory_asset_title_request WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, AssetTitleCorrectionSnapshot::class.java) }
    }
    fun get(id: UUID): AssetTitleCorrectionSnapshot = find(id) ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Title request not found"))
    fun replay(key: String, hash: String, actor: UUID): AssetTitleCorrectionSnapshot? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "title-correction:${sql.tenant}:$key")
        sql.query("SELECT id,actor_id,payload_hash FROM inventory_asset_title_request WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != actor) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            get(it.uuid("id"))
        }.singleOrNull()
    }
    fun insert(record: AssetTitleCorrectionSnapshot, key: String, hash: String) = jdbc.execute { sql ->
        val source = record.source
        val position = record.position.dimension
        val snapshot = mapper.writeValueAsString(record)
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,customer_id,work_order_id,work_order_revision,
            cutover_epoch,authority_epoch,source_document_id,source_revision,source_reference)
            VALUES (?,?,?,'TITLE_CORRECTION',?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant, record.code, record.actorId,
            source.customerId, source.workOrderId, record.workOrderRevision, record.cutoverEpoch, record.authorityEpoch,
            record.handover.operationId, 1, record.id.toString())
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,tracking,
            quantity_base,stock_identity_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,0,1,?,'EA','SERIAL',1,?,?,?,?,'CUSTOMER','SERVICEABLE',?)""", record.id, sql.tenant, record.id,
            position.skuId, source.assetId, source.assignmentId, position.locationId, source.customerId, source.legalOwner)
        sql.update("""INSERT INTO inventory_asset_title_request(id,tenant_id,assignment_id,handover_id,asset_id,customer_id,work_order_id,
            actor_id,source_assignment_revision,source_title_revision,source_asset_revision,source_owner,target_owner,evidence_id,evidence_digest,
            reason,operation_key,payload_hash,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant,
            source.assignmentId, record.handover.id, source.assetId, source.customerId, source.workOrderId, record.actorId,
            source.assignmentRevision, source.titleRevision, record.position.assetRevision, source.legalOwner, record.targetOwner,
            record.evidence.id, record.evidence.digest, record.reason, key, hash, snapshot)
        Unit
    }
    fun guard(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval = jdbc.execute { sql ->
        ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(mapper.writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")), ApprovalPostingKind.TITLE_CORRECTION)
    }
    fun transfer(request: AssetTitleCorrectionSnapshot, approvalId: UUID, operation: PostingOperation) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_asset_title_transfer(id,tenant_id,request_id,approval_id,operation_id,posting_id,assignment_id,
            handover_id,asset_id,customer_id,work_order_id,source_owner,target_owner,source_assignment_revision,assignment_revision,
            source_title_revision,title_revision,source_asset_revision,actor_id,recorded_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, request.id, approvalId, operation.id,
            operation.postingId, request.source.assignmentId, request.handover.id, request.source.assetId, request.source.customerId,
            request.source.workOrderId, request.source.legalOwner, request.targetOwner, request.source.assignmentRevision,
            Math.addExact(request.source.assignmentRevision, 1), request.source.titleRevision, Math.addExact(request.source.titleRevision, 1),
            request.position.assetRevision, operation.actorId, operation.recordedAt)
        sql.update("""INSERT INTO inventory_asset_recovery_transition(tenant_id,transfer_id,assignment_id,asset_id,customer_id,required)
            VALUES (?,?,?,?,?,?)""", sql.tenant, operation.id, request.source.assignmentId, request.source.assetId,
            request.source.customerId, request.targetOwner == AssetLegalOwner.ISP)
        sql.value("SELECT warehouse_apply_asset_title_transfer(?,?)", sql.tenant, operation.id)
        Unit
    }
}
