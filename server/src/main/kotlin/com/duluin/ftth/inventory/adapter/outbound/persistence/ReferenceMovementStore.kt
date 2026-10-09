package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReferenceMovementStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    private val joins = """FROM inventory_reference_post binding
        JOIN inventory_document document ON document.tenant_id=binding.tenant_id AND document.id=binding.document_id
        JOIN inventory_operation operation ON operation.tenant_id=binding.tenant_id AND operation.id=binding.id
        JOIN inventory_location destination ON destination.tenant_id=binding.tenant_id AND destination.id=binding.destination_location_id
        JOIN inventory_location source ON source.tenant_id=binding.tenant_id AND source.id=binding.source_location_id
        LEFT JOIN app_user actor ON actor.tenant_id=operation.tenant_id AND actor.id=operation.actor_id
        LEFT JOIN inventory_receipt_intake intake ON intake.tenant_id=document.tenant_id AND intake.id=document.id"""
    private val columns = """document.id,document.code,document.revision,document.state,operation.id operation_id,
        binding.action,operation.created_at,operation.original_body::jsonb->>'notes' notes,
        coalesce(actor.name,'Pengguna lama') actor_name,binding.destination_location_id,binding.source_location_id,
        coalesce(destination.name,destination.code) destination_name,coalesce(source.name,source.code) source_name,
        intake.snapshot::jsonb#>>'{supplier,name}' supplier_name,intake.snapshot::jsonb->>'externalReference' reference"""

    private fun predicate(locations: Set<UUID>) = """binding.tenant_id=? AND binding.action IN ('RECEIPT','TRANSFER')
        AND binding.destination_location_id IN (${locations.joinToString(",") { "?" }})
        AND (binding.action='RECEIPT' OR binding.source_location_id IN (${locations.joinToString(",") { "?" }}))"""

    fun list(locations: Set<UUID>, costVisible: Boolean, kind: String?, page: Int, size: Int, search: String?): WarehousePage<ReferenceMovementSummary> = jdbc.execute { sql ->
        if (locations.isEmpty()) return@execute WarehousePage(emptyList(), page, size, 0)
        val where = predicate(locations) + (if (kind == null) "" else " AND binding.action=?") +
            " AND (document.code ILIKE ? OR coalesce(document.source_reference,'') ILIKE ? OR coalesce(operation.original_body::jsonb->>'notes','') ILIKE ?)"
        val term = "%${search?.trim().orEmpty()}%"
        val values = buildList<Any?> { add(sql.tenant); addAll(locations); addAll(locations); if (kind != null) add(kind); repeat(3) { add(term) } }.toTypedArray()
        val total = requireNotNull(sql.value("SELECT count(*) $joins WHERE $where", *values)).toLong()
        val rows = sql.query("SELECT $columns $joins WHERE $where ORDER BY operation.created_at DESC,document.id LIMIT ? OFFSET ?",
            *values, size, page.toLong() * size) { summary(it, costVisible) }
        WarehousePage(rows, page, size, total)
    }

    fun get(id: UUID, locations: Set<UUID>, costVisible: Boolean): ReferenceMovementSummary = jdbc.execute { sql ->
        if (locations.isEmpty()) sql.fail(WarehouseErrorCode.NOT_FOUND)
        sql.query("SELECT $columns $joins WHERE ${predicate(locations)} AND document.id=?",
            sql.tenant, *locations.toTypedArray(), *locations.toTypedArray(), id) { summary(it, costVisible) }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun lines(document: ReferenceMovementSummary, page: Int, size: Int): WarehousePage<ReferenceMovementLineView> = jdbc.execute { sql ->
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_document_line WHERE tenant_id=? AND document_id=?", sql.tenant, document.id)).toLong()
        val rows = sql.query("""SELECT line.id,line.line_number,line.sku_id,line.tracking,line.base_unit,line.quantity_base,
            coalesce(snapshot.value#>>'{sku,code}',sku.code) sku_code,coalesce(snapshot.value#>>'{sku,name}',sku.name) sku_name,
            coalesce(snapshot.value->>'serial',asset.serial_number) serial,coalesce(snapshot.value->>'mac',asset.mac_address) mac,
            coalesce(snapshot.value->>'lotCode',lot.code) lot_code,snapshot.value->>'conversion' conversion,snapshot.value->>'cost' cost
            FROM inventory_document_line line
            JOIN inventory_sku sku ON sku.tenant_id=line.tenant_id AND sku.id=line.sku_id
            LEFT JOIN inventory_receipt_intake intake ON intake.tenant_id=line.tenant_id AND intake.id=line.document_id
            LEFT JOIN LATERAL (SELECT value FROM jsonb_array_elements(intake.snapshot::jsonb->'lines') value WHERE value->>'id'=line.id::text) snapshot ON true
            LEFT JOIN inventory_segment segment ON segment.tenant_id=line.tenant_id AND segment.id=line.stock_identity_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.id=line.lot_id
            WHERE line.tenant_id=? AND line.document_id=? ORDER BY line.line_number,line.id LIMIT ? OFFSET ?""",
            sql.tenant, document.id, size, page.toLong() * size) {
            ReferenceMovementLineView(it.uuid("id"), it.getInt("line_number"), it.uuid("sku_id"), it.getString("sku_code"), it.getString("sku_name"),
                WarehouseTracking.valueOf(it.getString("tracking")), WarehouseBaseUnit.valueOf(it.getString("base_unit")), it.getLong("quantity_base").toString(),
                it.getString("serial"), it.getString("mac"), it.getString("lot_code"),
                it.getString("conversion")?.let { json -> mapper.readValue(json, ReceiptPackageInput::class.java) },
                if (document.costVisible) it.getString("cost")?.let { json -> mapper.readValue(json, ReceiptCostSnapshot::class.java) } else null)
        }
        WarehousePage(rows, page, size, total)
    }

    private fun summary(row: java.sql.ResultSet, costVisible: Boolean): ReferenceMovementSummary {
        val transfer = row.getString("action") == "TRANSFER"
        return ReferenceMovementSummary(row.uuid("id"), row.getString("code"), row.uuid("operation_id"), row.getLong("revision"), row.getString("action"),
            row.getString("state"), row.getString("notes") ?: "", row.getTimestamp("created_at").toInstant(), row.getString("actor_name"),
            row.uuid("destination_location_id"), row.getString("destination_name"), if (transfer) row.uuid("source_location_id") else null,
            if (transfer) row.getString("source_name") else null, row.getString("supplier_name"), row.getString("reference"), costVisible)
    }
}
