package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.service.WarehouseDispositionRecord
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseDispositionStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun workOrder(returnId: UUID): UUID = jdbc.execute { sql ->
        sql.query("""SELECT coalesce(residual.work_order_id,removal.work_order_id) work_order_id
            FROM inventory_return_case returned
            LEFT JOIN inventory_material_residual residual ON residual.tenant_id=returned.tenant_id
                AND residual.id=returned.source_document_id AND returned.origin='MATERIAL_RESIDUAL'
            LEFT JOIN inventory_asset_removal removal ON removal.tenant_id=returned.tenant_id
                AND removal.id=returned.source_document_id AND returned.origin='ASSET_REMOVAL'
            WHERE returned.tenant_id=? AND returned.id=?""", sql.tenant, returnId) { it.uuid("work_order_id") }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun lockPhysical(identity: UUID, validate: Boolean = true): Long? = jdbc.execute { sql ->
        val asset = sql.value("SELECT revision FROM inventory_serialized_asset WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, identity)?.toLong()
        if (validate && sql.value("SELECT id FROM inventory_asset_assignment WHERE tenant_id=? AND asset_id=? AND ended_at IS NULL LIMIT 1", sql.tenant, identity) != null)
            sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        sql.query("SELECT id FROM inventory_segment WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, identity) { it.uuid("id") }
        sql.query("SELECT id FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=? ORDER BY id FOR UPDATE", sql.tenant, identity) { it.uuid("id") }
        if (validate && sql.value("SELECT id FROM inventory_reservation WHERE tenant_id=? AND stock_identity_id=? AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0) LIMIT 1",
                sql.tenant, identity) != null) sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
        asset
    }

    fun find(id: UUID): WarehouseDispositionRecord? = jdbc.execute { sql ->
        sql.value("SELECT snapshot FROM inventory_disposition_request WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, WarehouseDispositionRecord::class.java) }
    }

    fun get(id: UUID): WarehouseDispositionRecord = find(id)
        ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, WarehouseErrorCode.NOT_FOUND.name))

    fun replay(key: String, hash: String, actor: UUID): WarehouseDispositionRecord? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "disposition:${sql.tenant}:$key")
        sql.query("SELECT actor_id,payload_hash,snapshot FROM inventory_disposition_request WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != actor) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            mapper.readValue(it.getString("snapshot"), WarehouseDispositionRecord::class.java)
        }.singleOrNull()
    }

    fun insert(record: WarehouseDispositionRecord, key: String, canonical: WarehouseCanonicalPayload) = jdbc.execute { sql ->
        val source = record.source
        val dimension = source.dimension
        val input = record.input
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,authority_epoch,
            cutover_epoch,source_document_id,source_revision,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            record.id, sql.tenant, record.code, input.action, record.actorId, record.context.workOrderId, record.authorityEpoch,
            record.cutoverEpoch, input.sourceDocumentId, input.expectedRevision, input.reason)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
            base_unit,tracking,quantity_base,stock_identity_id,lot_id,source_line_id,location_id,destination_location_id,
            custodian_id,custodian_kind,condition,legal_owner,cost_total_minor,cost_basis_quantity_base,currency)
            VALUES (?,?,?,0,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant, record.id,
            dimension.skuId, source.unit, source.tracking, source.quantity, input.stockIdentityId, dimension.lotId,
            input.sourceDocumentId, dimension.locationId, input.destinationLocationId, dimension.custodianId,
            dimension.custodianKind, dimension.condition, dimension.legalOwner, record.cost?.totalMinor?.toLong(),
            record.cost?.costBasisQuantityBase?.toLong(), record.cost?.currency)
        sql.update("""INSERT INTO inventory_disposition_request(id,tenant_id,source_document_id,stock_identity_id,actor_id,
            operation_key,payload_hash,canonical_payload,snapshot) VALUES (?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant,
            input.sourceDocumentId, input.stockIdentityId, record.actorId, key, canonical.hash, canonical.json, mapper.writeValueAsString(record))
        Unit
    }

    fun view(id: UUID): WarehouseDispositionView = jdbc.execute { sql ->
        sql.query("""SELECT request.snapshot,document.revision,document.state,document.approval_disposition
            FROM inventory_disposition_request request JOIN inventory_document document
                ON document.tenant_id=request.tenant_id AND document.id=request.id
            WHERE request.tenant_id=? AND request.id=?""", sql.tenant, id) {
            mapper.readValue(it.getString("snapshot"), WarehouseDispositionRecord::class.java)
                .view(it.getLong("revision"), state(it.getString("state"), it.getString("approval_disposition")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(filter: WarehouseDispositionFilter, access: WarehouseQueryAccess): WarehousePage<WarehouseDispositionView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(filter.page, filter.size, "createdAt", "desc"), access)
        val rows = """SELECT disposition.id,disposition.created_at,jsonb_build_object('snapshot',disposition.snapshot::jsonb,
                'revision',document.revision,'state',document.state,'approvalDisposition',document.approval_disposition) body
            FROM inventory_disposition_request disposition JOIN inventory_document document
                ON document.tenant_id=disposition.tenant_id AND document.id=disposition.id,request
            WHERE disposition.tenant_id=request.tenant
                AND (disposition.snapshot::jsonb#>>'{source,dimension,locationId}')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (disposition.snapshot::jsonb#>>'{input,destinationLocationId}')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (disposition.snapshot::jsonb#>>'{returned,intake,quarantineLocationId}')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (?::uuid IS NULL OR disposition.source_document_id=?::uuid)
                AND (?::text IS NULL OR document.kind=?::text)"""
        val result = mapper.readTree(query.result(query.page(rows, "body", "created_at"), filter.sourceDocumentId,
            filter.sourceDocumentId, filter.action?.name, filter.action?.name))
        WarehousePage(result.path("items").asSequence().map { row ->
            mapper.treeToValue(row.path("snapshot"), WarehouseDispositionRecord::class.java).view(row.path("revision").asLong(),
                state(row.path("state").asString(), row.path("approvalDisposition").asString(null)))
        }.toList(), filter.page, filter.size, result.path("totalElements").asLong())
    }

    private fun state(state: String, disposition: String?): WarehouseDispositionState = when {
        disposition == "REWORK_REQUIRED" -> WarehouseDispositionState.REWORK_REQUIRED
        state == "POSTED" -> WarehouseDispositionState.POSTED
        state == "DRAFT" -> WarehouseDispositionState.DRAFT
        else -> masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
}
