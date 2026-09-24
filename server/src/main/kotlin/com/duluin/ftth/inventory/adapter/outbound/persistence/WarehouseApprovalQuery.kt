package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class ApprovalQueryCursor(val id: UUID, val requestedAt: Instant)

@Repository
class WarehouseApprovalQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    /** Fixed-size keyset batches; current owner gates run before counting/selecting the public page. */
    fun candidates(filter: WarehouseApprovalFilter, access: WarehouseQueryAccess, cursor: ApprovalQueryCursor?): List<ApprovalQueryCursor> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(skuId = filter.skuId, serial = filter.serial,
            locationId = filter.locationId, status = filter.status?.name, from = filter.from, until = filter.until), access)
        val result = query.result(""", candidates AS (SELECT approval.id,approval.requested_at FROM inventory_approval approval,request
            WHERE approval.tenant_id=request.tenant AND approval.evaluation_snapshot IS NOT NULL
                AND NOT EXISTS (SELECT FROM unnest(approval.location_ids) location(id)
                    WHERE location.id NOT IN (SELECT id FROM visible_locations WHERE state='ACTIVE'))
                AND (request.location IS NULL OR request.location=ANY(approval.location_ids))
                AND (request.status IS NULL OR request.status=CASE WHEN approval.status='PENDING' AND approval.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE approval.status END)
                AND (?::uuid IS NULL OR approval.source_document_id=?::uuid)
                AND (?::text IS NULL OR position(lower(?::text) IN lower(approval.source_snapshot::jsonb->'document'->>'code'))>0)
                AND (?::text IS NULL OR approval.business_action=?::text)
                AND (request.since IS NULL OR approval.requested_at>=request.since)
                AND (request.until IS NULL OR approval.requested_at<request.until)
                AND ((request.sku IS NULL AND request.serial IS NULL) OR EXISTS (
                    SELECT FROM jsonb_array_elements(approval.source_snapshot::jsonb->'lines') line
                    LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=approval.tenant_id AND asset.id=(line->>'stock_identity_id')::uuid
                    WHERE (request.sku IS NULL OR (line->>'sku_id')::uuid=request.sku)
                        AND (request.serial IS NULL OR asset.canonical_serial=request.serial OR EXISTS (
                            SELECT FROM jsonb_array_elements((approval.source_snapshot::jsonb->'intake'->>'snapshot')::jsonb->'lines') intake
                            WHERE intake->>'id'=line->>'id' AND warehouse_canonical_serial(intake->>'serial')=request.serial))))
                AND (?::timestamptz IS NULL OR approval.requested_at<?::timestamptz OR (approval.requested_at=?::timestamptz AND approval.id>?::uuid))
            ORDER BY approval.requested_at DESC,approval.id LIMIT 100)
            SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'requestedAt',${queryTime("requested_at")}) ORDER BY requested_at DESC,id),'[]'::jsonb)::text FROM candidates""",
            filter.sourceDocumentId, filter.sourceDocumentId, filter.query, filter.query, filter.operation?.name, filter.operation?.name,
            cursor?.requestedAt, cursor?.requestedAt, cursor?.requestedAt, cursor?.id)
        mapper.readTree(result).asSequence().map { ApprovalQueryCursor(UUID.fromString(it.path("id").asString()), Instant.parse(it.path("requestedAt").asString())) }.toList()
    }
    fun item(id: UUID): Triple<String, String, WarehouseTracking> = jdbc.execute { sql ->
        sql.query("SELECT code,name,tracking FROM inventory_sku WHERE tenant_id=? AND id=?", sql.tenant, id) {
            Triple(it.getString("code"), it.getString("name"), WarehouseTracking.valueOf(it.getString("tracking")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun serial(id: UUID?): String? = id?.let { jdbc.execute { sql -> sql.value("SELECT serial_number FROM inventory_serialized_asset WHERE tenant_id=? AND id=?", sql.tenant, id) } }
    fun lot(id: UUID?): String? = id?.let { jdbc.execute { sql -> sql.value("SELECT code FROM inventory_lot WHERE tenant_id=? AND id=?", sql.tenant, id) } }
    fun locations(ids: Set<UUID>): List<WarehouseApprovalLocation> = jdbc.execute { sql -> ids.sortedBy(UUID::toString).map { id ->
        sql.query("SELECT id,code,name FROM inventory_location WHERE tenant_id=? AND id=?", sql.tenant, id) {
            WarehouseApprovalLocation(it.uuid("id"), it.getString("code"), it.getString("name"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    } }
    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseApprovalDecisionRecord> = jdbc.execute { sql ->
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_approval_decision WHERE tenant_id=? AND approval_id=?", sql.tenant, id)).toLong()
        val rows = sql.query("SELECT independence_snapshot FROM inventory_approval_decision WHERE tenant_id=? AND approval_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",
            sql.tenant, id, page.size, page.page.toLong() * page.size) { mapper.readValue(it.getString(1), WarehouseApprovalDecisionRecord::class.java) }
        WarehousePage(rows, page.page, page.size, total)
    }
    fun effect(id: UUID, operationId: UUID): WarehouseApprovalEffectView = jdbc.execute { sql ->
        val movements = sql.query("SELECT id FROM inventory_movement WHERE tenant_id=? AND operation_id=? AND state='APPLIED' ORDER BY id", sql.tenant, operationId) { it.uuid("id") }
        sql.query("""SELECT operation.business_action,operation.created_at FROM inventory_approval_effect effect
            JOIN inventory_operation operation ON operation.tenant_id=effect.tenant_id AND operation.id=effect.posting_operation_id
            WHERE effect.tenant_id=? AND effect.approval_id=? AND effect.posting_operation_id=?""", sql.tenant, id, operationId) {
            WarehouseApprovalEffectView(operationId, it.getString("business_action"), it.getTimestamp("created_at").toInstant(), movements)
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
}
