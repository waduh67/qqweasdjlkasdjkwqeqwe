package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class SupplierReplacementStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun context(id: UUID): SupplierReplacementContext = jdbc.execute { sql ->
        sql.query("""SELECT assignment.id,assignment.customer_id,assignment.work_order_id,asset.revision
            FROM inventory_return_case returned JOIN inventory_asset_removal removal
                ON removal.tenant_id=returned.tenant_id AND removal.id=returned.source_document_id
            JOIN inventory_asset_assignment assignment ON assignment.tenant_id=removal.tenant_id AND assignment.id=removal.assignment_id
            JOIN inventory_serialized_asset asset ON asset.tenant_id=returned.tenant_id AND asset.id=returned.stock_identity_id
            WHERE returned.tenant_id=? AND returned.id=? AND returned.origin='ASSET_REMOVAL'
                AND assignment.ended_at IS NOT NULL AND assignment.warehouse_admission='VERIFIED'
                AND asset.warehouse_admission='VERIFIED' AND assignment.asset_id=asset.id""", sql.tenant, id) {
            SupplierReplacementContext(it.uuid("id"), it.uuid("customer_id"), it.uuid("work_order_id"), it.getLong("revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockAsset(id: UUID): Long = jdbc.execute { sql ->
        sql.query("SELECT revision FROM inventory_serialized_asset WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id) {
            it.getLong("revision")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun replay(key: String, hash: String, actor: UUID): SupplierReplacementRecord? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "replacement-receipt:${sql.tenant}:$key")
        sql.query("SELECT snapshot,payload_hash,actor_id FROM inventory_repair_replacement_request WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != actor) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            mapper.readValue(it.getString("snapshot"), SupplierReplacementRecord::class.java)
        }.singleOrNull()
    }

    fun insert(record: SupplierReplacementRecord, key: String, canonical: WarehouseCanonicalPayload) = jdbc.execute { sql ->
        val view = record.view
        sql.update("UPDATE inventory_document SET customer_id=?,work_order_id=?,work_order_revision=? WHERE tenant_id=? AND id=?",
            record.context.customerId, record.context.workOrderId, record.workOrderRevision, sql.tenant, view.receiptId)
        sql.update("UPDATE inventory_document_line SET legal_owner=? WHERE tenant_id=? AND document_id=?",
            view.legalOwner, sql.tenant, view.receiptId)
        sql.update("""INSERT INTO inventory_repair_replacement_request(id,tenant_id,return_id,repair_case_id,receipt_id,
            original_asset_id,assignment_id,customer_id,work_order_id,legal_owner,actor_id,source_return_revision,
            source_asset_revision,operation_key,payload_hash,canonical_payload,snapshot)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", view.id, sql.tenant, view.returnId, view.repairCaseId, view.receiptId,
            view.originalAssetId, record.context.assignmentId, record.context.customerId, record.context.workOrderId, view.legalOwner,
            record.actorId, record.returned.view.revision, record.context.assetRevision, key, canonical.hash, canonical.json,
            mapper.writeValueAsString(record))
        Unit
    }

    fun list(id: UUID, page: WarehousePageRequest): List<Pair<SupplierReplacementRecord, UUID?>> = jdbc.execute { sql ->
        sql.query("""SELECT request.snapshot,line.stock_identity_id FROM inventory_repair_replacement_request request
            JOIN inventory_document_line line ON line.tenant_id=request.tenant_id AND line.document_id=request.receipt_id
            WHERE request.tenant_id=? AND request.return_id=? ORDER BY request.created_at,request.id LIMIT ? OFFSET ?""",
            sql.tenant, id, page.size, page.page.toLong() * page.size) {
            mapper.readValue(it.getString("snapshot"), SupplierReplacementRecord::class.java) to it.optionalUuid("stock_identity_id")
        }
    }
}
