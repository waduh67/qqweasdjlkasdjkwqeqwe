package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.service.WarehouseCompensationRecord
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseCompensationStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun originalPosting(id: UUID): Pair<UUID, Long> = jdbc.execute { sql ->
        sql.query("""SELECT movement.id,effect.return_revision FROM inventory_disposition_effect effect
            JOIN inventory_movement movement ON movement.tenant_id=effect.tenant_id AND movement.operation_id=effect.posting_operation_id
            WHERE effect.tenant_id=? AND effect.request_id=? AND movement.state='APPLIED'""", sql.tenant, id) {
            it.uuid("id") to it.getLong("return_revision")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun hasCompensation(posting: UUID): Boolean = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_movement WHERE tenant_id=? AND compensates_movement_id=? LIMIT 1", sql.tenant, posting) != null
    }

    fun materialRevision(workOrder: UUID): Long? = jdbc.execute { sql ->
        sql.query("SELECT revision,material_state FROM inventory_material_lifecycle WHERE tenant_id=? AND work_order_id=? ORDER BY revision DESC LIMIT 1",
            sql.tenant, workOrder) {
            if (it.getString("material_state") == "CLOSED") sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            it.getLong("revision")
        }.singleOrNull()
    }

    fun find(id: UUID): WarehouseCompensationRecord? = jdbc.execute { sql ->
        sql.value("SELECT snapshot FROM inventory_compensation_request WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, WarehouseCompensationRecord::class.java) }
    }
    fun get(id: UUID): WarehouseCompensationRecord = find(id) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)

    fun replay(key: String, hash: String, actor: UUID): WarehouseCompensationRecord? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "disposition-compensation:${sql.tenant}:$key")
        sql.query("SELECT actor_id,payload_hash,snapshot FROM inventory_compensation_request WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != actor) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            mapper.readValue(it.getString("snapshot"), WarehouseCompensationRecord::class.java)
        }.singleOrNull()
    }

    fun insert(record: WarehouseCompensationRecord, key: String, canonical: WarehouseCanonicalPayload) = jdbc.execute { sql ->
        val dimension = record.source.dimension
        val cost = record.original.cost
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,authority_epoch,
            cutover_epoch,source_document_id,source_revision,reason) VALUES (?,?,?,'DISPOSITION_REVERSAL',?,?,?,?,?,?,?)""",
            record.id, sql.tenant, record.code, record.actorId, record.context.workOrderId, record.authorityEpoch,
            record.cutoverEpoch, record.original.id, record.input.expectedRevision, record.input.reason)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
            base_unit,tracking,quantity_base,stock_identity_id,lot_id,source_line_id,location_id,destination_location_id,
            custodian_id,custodian_kind,condition,legal_owner,cost_total_minor,cost_basis_quantity_base,currency)
            VALUES (?,?,?,0,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant, record.id,
            dimension.skuId, record.source.unit, record.source.tracking, record.source.quantity, dimension.stockIdentityId,
            dimension.lotId, record.original.id, dimension.locationId, record.input.destinationLocationId,
            dimension.custodianId, dimension.custodianKind, dimension.condition, dimension.legalOwner,
            cost?.totalMinor?.toLong(), cost?.costBasisQuantityBase?.toLong(), cost?.currency)
        sql.update("""INSERT INTO inventory_compensation_request(id,tenant_id,original_request_id,original_posting_id,return_id,
            stock_identity_id,actor_id,operation_key,payload_hash,canonical_payload,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            record.id, sql.tenant, record.original.id, record.originalPostingId, record.returned.view.id, dimension.stockIdentityId,
            record.actorId, key, canonical.hash, canonical.json, mapper.writeValueAsString(record))
        Unit
    }

    fun view(id: UUID): WarehouseCompensationView = jdbc.execute { sql ->
        sql.query("""SELECT request.snapshot,document.revision,document.state,document.approval_disposition
            FROM inventory_compensation_request request JOIN inventory_document document
                ON document.tenant_id=request.tenant_id AND document.id=request.id WHERE request.tenant_id=? AND request.id=?""", sql.tenant, id) {
            mapper.readValue(it.getString("snapshot"), WarehouseCompensationRecord::class.java)
                .view(it.getLong("revision"), state(it.getString("state"), it.getString("approval_disposition")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(original: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<WarehouseCompensationView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page.page, page.size, "createdAt", "desc"), access)
        val rows = """SELECT compensation.id,compensation.created_at,jsonb_build_object('snapshot',compensation.snapshot::jsonb,
                'revision',document.revision,'state',document.state,'approvalDisposition',document.approval_disposition) body
            FROM inventory_compensation_request compensation JOIN inventory_document document
                ON document.tenant_id=compensation.tenant_id AND document.id=compensation.id,request
            WHERE compensation.tenant_id=request.tenant AND compensation.original_request_id=?
                AND (compensation.snapshot::jsonb#>>'{input,destinationLocationId}')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')"""
        val result = mapper.readTree(query.result(query.page(rows, "body", "created_at"), original))
        WarehousePage(result.path("items").asSequence().map {
            mapper.treeToValue(it.path("snapshot"), WarehouseCompensationRecord::class.java).view(it.path("revision").asLong(),
                state(it.path("state").asString(), it.path("approvalDisposition").asString(null)))
        }.toList(), page.page, page.size, result.path("totalElements").asLong())
    }

    private fun state(state: String, disposition: String?): WarehouseDispositionState = when {
        disposition == "REWORK_REQUIRED" -> WarehouseDispositionState.REWORK_REQUIRED
        state == "POSTED" -> WarehouseDispositionState.POSTED
        state == "DRAFT" -> WarehouseDispositionState.DRAFT
        else -> masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

}
