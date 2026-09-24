package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.WarehouseAssetLossRecord
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseAssetLossStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun find(id: UUID): WarehouseAssetLossRecord? = jdbc.execute { sql ->
        sql.value("SELECT snapshot FROM inventory_asset_loss_request WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, WarehouseAssetLossRecord::class.java) }
    }

    fun get(id: UUID): WarehouseAssetLossRecord = find(id) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)

    fun replay(key: String, hash: String, actor: UUID): WarehouseAssetLossRecord? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "asset-loss:${sql.tenant}:$key")
        sql.query("SELECT actor_id,payload_hash,snapshot FROM inventory_asset_loss_request WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != actor) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            mapper.readValue(it.getString("snapshot"), WarehouseAssetLossRecord::class.java)
        }.singleOrNull()
    }

    fun insert(record: WarehouseAssetLossRecord, key: String, canonical: WarehouseCanonicalPayload) = jdbc.execute { sql ->
        val input = record.input
        val dimension = record.position.dimension
        val cost = record.source.cost
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,work_order_revision,
            authority_epoch,cutover_epoch,source_document_id,source_revision,reason)
            VALUES (?,?,?,'ASSET_LOSS',?,?,?,?,?,?,1,?)""", record.id, sql.tenant, record.code, record.actorId,
            record.ownership.workOrderId, record.workOrderRevision, record.authorityEpoch, record.cutoverEpoch, input.sourceHandoverId, input.reason)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
            base_unit,tracking,quantity_base,stock_identity_id,source_line_id,location_id,destination_location_id,
            custodian_id,custodian_kind,condition,legal_owner,cost_total_minor,cost_basis_quantity_base,currency)
            VALUES (?,?,?,0,1,?,'EA','SERIAL',1,?,?,?,?,?,'CUSTOMER','SERVICEABLE','ISP',?,?,?)""",
            record.id, sql.tenant, record.id, dimension.skuId, record.ownership.assetId, input.assignmentId,
            dimension.locationId, input.destinationLocationId, dimension.custodianId,
            cost?.totalMinor?.toLong(), cost?.costBasisQuantityBase?.toLong(), cost?.currency)
        sql.update("""INSERT INTO inventory_asset_loss_request(id,tenant_id,assignment_id,handover_id,asset_id,actor_id,
            operation_key,payload_hash,canonical_payload,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant,
            input.assignmentId, input.sourceHandoverId, record.ownership.assetId, record.actorId, key, canonical.hash, canonical.json,
            mapper.writeValueAsString(record))
        Unit
    }

    fun view(id: UUID): WarehouseAssetLossView = jdbc.execute { sql ->
        sql.query("""SELECT request.snapshot,document.revision,document.state,document.approval_disposition
            FROM inventory_asset_loss_request request JOIN inventory_document document
                ON document.tenant_id=request.tenant_id AND document.id=request.id
            WHERE request.tenant_id=? AND request.id=?""", sql.tenant, id) {
            mapper.readValue(it.getString("snapshot"), WarehouseAssetLossRecord::class.java)
                .view(it.getLong("revision"), state(it.getString("state"), it.getString("approval_disposition")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(page: Int, size: Int, access: WarehouseQueryAccess): WarehousePage<WarehouseAssetLossView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page, size, "createdAt", "desc"), access)
        val rows = """SELECT loss.id,loss.created_at,jsonb_build_object('snapshot',loss.snapshot::jsonb,
                'revision',document.revision,'state',document.state,'approvalDisposition',document.approval_disposition) body
            FROM inventory_asset_loss_request loss JOIN inventory_document document
                ON document.tenant_id=loss.tenant_id AND document.id=loss.id,request
            WHERE loss.tenant_id=request.tenant
                AND (loss.snapshot::jsonb#>>'{position,dimension,locationId}')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (loss.snapshot::jsonb#>>'{input,destinationLocationId}')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')"""
        val result = mapper.readTree(query.result(query.page(rows, "body", "created_at")))
        WarehousePage(result.path("items").asSequence().map { row ->
            mapper.treeToValue(row.path("snapshot"), WarehouseAssetLossRecord::class.java).view(row.path("revision").asLong(),
                state(row.path("state").asString(), row.path("approvalDisposition").asString(null)))
        }.toList(), page, size, result.path("totalElements").asLong())
    }

    private fun state(state: String, disposition: String?): WarehouseDispositionState = when {
        disposition == "REWORK_REQUIRED" -> WarehouseDispositionState.REWORK_REQUIRED
        state == "POSTED" -> WarehouseDispositionState.POSTED
        state == "DRAFT" -> WarehouseDispositionState.DRAFT
        else -> masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
}
