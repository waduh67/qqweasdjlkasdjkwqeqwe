package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReturnReacquisitionStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun list(id: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<ReturnReacquisitionEntry> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page.page, page.size, "createdAt", "desc"), access)
        val rows = """SELECT document.id,document.created_at,
                jsonb_build_object('snapshot',title.snapshot::jsonb,'appliedReturnRevision',effect.return_revision) body
            FROM inventory_return_title_request title
            JOIN inventory_document document ON document.tenant_id=title.tenant_id AND document.id=title.id
            LEFT JOIN inventory_return_title_effect effect ON effect.tenant_id=title.tenant_id AND effect.request_id=title.id,request
            WHERE title.tenant_id=request.tenant AND title.return_id=?::uuid
                AND (title.snapshot::jsonb->'source'->'dimension'->>'locationId')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (title.snapshot::jsonb->'returned'->'view'->'repair'->>'repairLocationId' IS NULL OR
                    (title.snapshot::jsonb->'returned'->'view'->'repair'->>'repairLocationId')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE'))"""
        val result = mapper.readTree(query.result(query.page(rows, "body", "created_at"), id))
        val items = result.path("items").map { item ->
            val record = mapper.treeToValue(item.path("snapshot"), ReturnTitleRecord::class.java)
            ReturnReacquisitionEntry(record.id, record.returned.view.id, 0, record.code, record.returned.view.revision,
                record.request.reason, record.request.titleTransferReference, record.signature.id, record.recordedAt,
                item.path("appliedReturnRevision").takeUnless { it.isNull }?.asLong())
        }
        WarehousePage(items, page.page, page.size, result.path("totalElements").asLong())
    }

    fun context(id: UUID): ReturnTitleContext = jdbc.execute { sql ->
        sql.query("""SELECT assignment.id,assignment.customer_id,assignment.work_order_id,assignment.revision,
            handover.id handover_id,handover.actor_id handover_actor,removal.actor_id removal_actor
            FROM inventory_return_case returned JOIN inventory_asset_removal removal
                ON removal.tenant_id=returned.tenant_id AND removal.id=returned.source_document_id
            JOIN inventory_asset_assignment assignment ON assignment.tenant_id=removal.tenant_id AND assignment.id=removal.assignment_id
            JOIN inventory_asset_handover handover ON handover.tenant_id=assignment.tenant_id AND handover.assignment_id=assignment.id
            WHERE returned.tenant_id=? AND returned.id=? AND returned.origin='ASSET_REMOVAL'
                AND assignment.ended_at IS NOT NULL AND assignment.legal_owner='CUSTOMER' AND assignment.ownership_mode='SALE'
                AND assignment.warehouse_admission='VERIFIED' AND removal.legal_owner='CUSTOMER'""", sql.tenant, id) {
            ReturnTitleContext(it.uuid("id"), it.uuid("customer_id"), it.uuid("work_order_id"), it.uuid("handover_id"),
                it.uuid("handover_actor"), it.uuid("removal_actor"), it.getLong("revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockAsset(id: UUID): Long = jdbc.execute { sql ->
        sql.query("SELECT revision FROM inventory_serialized_asset WHERE tenant_id=? AND id=? AND warehouse_admission='VERIFIED' FOR UPDATE",
            sql.tenant, id) { it.getLong("revision") }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun canReacquire(record: WarehouseReturnRecord): Boolean = jdbc.execute { sql ->
        sql.value("""SELECT 1 WHERE NOT EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=? AND asset_id=? AND ended_at IS NULL)
            AND NOT EXISTS(SELECT FROM inventory_repair_case WHERE tenant_id=? AND return_document_id=? AND state<>'CLOSED')
            AND EXISTS(SELECT FROM inventory_location WHERE tenant_id=? AND id=? AND kind='QUARANTINE' AND state='ACTIVE')""",
            sql.tenant, record.view.stockIdentityId, sql.tenant, record.view.id, sql.tenant, record.view.locationId) != null
    }

    fun find(id: UUID): ReturnTitleRecord? = jdbc.execute { sql ->
        if (sql.value("SELECT kind FROM inventory_document WHERE tenant_id=? AND id=?", sql.tenant, id) != "RETURN_TITLE") return@execute null
        sql.value("SELECT snapshot FROM inventory_return_title_request WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, ReturnTitleRecord::class.java) }
    }
    fun get(id: UUID): ReturnTitleRecord = find(id) ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Return title request not found"))

    fun replay(key: String, hash: String, actor: UUID): ReturnTitleRecord? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "return-title:${sql.tenant}:$key")
        sql.query("SELECT snapshot,payload_hash,actor_id FROM inventory_return_title_request WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != actor) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            mapper.readValue(it.getString("snapshot"), ReturnTitleRecord::class.java)
        }.singleOrNull()
    }

    fun insert(record: ReturnTitleRecord, key: String, hash: String) = jdbc.execute { sql ->
        val view = record.returned.view
        val position = record.source.dimension
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to view.id, "request" to record.request)))
        check(canonical.hash == hash)
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,customer_id,work_order_id,work_order_revision,
            cutover_epoch,authority_epoch,source_document_id,source_revision,source_reference,reason)
            VALUES (?,?,?,'RETURN_TITLE',?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant, record.code, record.actorId,
            record.context.customerId, record.context.workOrderId, record.workOrderRevision, record.cutoverEpoch, record.authorityEpoch,
            view.id, view.revision, record.id.toString(), record.request.reason)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,tracking,
            quantity_base,stock_identity_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,0,1,?,'EA','SERIAL',1,?,?,?,?,?,?,?)""", record.id, sql.tenant, record.id, position.skuId,
            view.stockIdentityId, view.id, position.locationId, position.custodianId, position.custodianKind, position.condition, position.legalOwner)
        sql.update("""INSERT INTO inventory_return_title_request(id,tenant_id,return_id,source_return_revision,asset_id,assignment_id,
            customer_id,work_order_id,actor_id,source_asset_revision,evidence_id,evidence_digest,operation_key,payload_hash,canonical_payload,snapshot)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant, view.id, view.revision, view.stockIdentityId,
            record.context.assignmentId, record.context.customerId, record.context.workOrderId, record.actorId, record.assetRevision,
            record.signature.id, record.signature.digest, key, hash, canonical.json, mapper.writeValueAsString(record))
        Unit
    }

    fun guard(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval = jdbc.execute { sql ->
        ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(mapper.writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")), ApprovalPostingKind.RETURN_TITLE)
    }

    fun complete(request: ReturnTitleRecord, approvalId: UUID, effect: PostingOperation): Pair<PostingOperation, String> = jdbc.execute { sql ->
        val previous = request.returned.view
        val view = previous.copy(revision = Math.addExact(previous.revision, 1), legalOwner = AssetLegalOwner.ISP, recordedAt = effect.recordedAt)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("returnId" to view.id,
            "requestId" to request.id, "approvalId" to approvalId, "sourceRevision" to previous.revision)))
        val operation = PostingOperation(UUID.randomUUID(), "warehouse.return.reacquire", approvalId.toString(), effect.actorId,
            view.id, "return:${view.id}", canonical.hash, "TITLE_REACQUIRED", 200, mapper.writeValueAsString(view), effect.authorityEpoch, effect.recordedAt)
        sql.update("""INSERT INTO inventory_return_title_effect(tenant_id,request_id,approval_id,posting_operation_id,return_operation_id,
            return_id,source_return_revision,return_revision) VALUES (?,?,?,?,?,?,?,?)""", sql.tenant, request.id, approvalId, effect.id,
            operation.id, view.id, previous.revision, view.revision)
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
            document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key, operation.actorId,
            view.id, operation.resourceScope, operation.payloadHash, view.id, view.revision, operation.businessAction, 200,
            operation.originalBody, request.cutoverEpoch, operation.authorityEpoch, operation.recordedAt)
        if (sql.update("UPDATE inventory_document SET revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND revision=? AND state='RECEIVED_IN_INSPECTION'",
                operation.recordedAt, sql.tenant, view.id, previous.revision) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        operation to canonical.json
    }
}
