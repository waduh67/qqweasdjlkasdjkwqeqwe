package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReturnReacquisitionStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

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
}
