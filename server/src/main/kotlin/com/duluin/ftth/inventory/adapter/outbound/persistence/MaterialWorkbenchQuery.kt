package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialWorkbenchQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun latestUsage(workOrder: UUID): UUID? = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_usage_snapshot WHERE tenant_id=? AND work_order_id=? ORDER BY use_revision DESC LIMIT 1",
            sql.tenant, workOrder) { it.uuid("id") }.singleOrNull()
    }

    /** Enumerate actual receipt descendants and intersect with current own physical custody before counting. */
    fun custody(workOrder: UUID, actor: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<MaterialCustodyChoice> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size), access)
        decode(query.result(""", context AS (SELECT ?::uuid work_order,?::uuid actor),
            receipts AS MATERIALIZED (SELECT receipt.*,line.issue_line_id,line.accepted_identity_id,
                issued.plan_id,entry->>'planLineId' plan_line,entry->'sku' sku
                FROM inventory_material_receipt receipt JOIN inventory_material_receipt_line line
                    ON line.tenant_id=receipt.tenant_id AND line.receipt_id=receipt.id
                JOIN inventory_issue_snapshot issued ON issued.tenant_id=receipt.tenant_id AND issued.id=receipt.issue_id
                JOIN inventory_document document ON document.tenant_id=issued.tenant_id AND document.id=issued.id
                CROSS JOIN LATERAL jsonb_array_elements(receipt.snapshot::jsonb->'issue'->'lines') entry,request,context
                WHERE receipt.tenant_id=request.tenant AND document.work_order_id=context.work_order
                    AND line.accepted_base>0 AND entry->>'id'=line.issue_line_id::text),
            sources AS (
                SELECT id receipt_id,issue_line_id,accepted_identity_id identity_id FROM receipts
                UNION SELECT line.receipt_id,line.issue_line_id,line.remainder_identity_id FROM inventory_material_usage_line line,request
                    WHERE line.tenant_id=request.tenant AND line.receipt_id IN (SELECT id FROM receipts) AND line.remainder_identity_id IS NOT NULL
                UNION SELECT residual.receipt_id,residual.issue_line_id,residual.remainder_identity_id FROM inventory_material_residual residual,request,context
                    WHERE residual.tenant_id=request.tenant AND residual.work_order_id=context.work_order AND residual.remainder_identity_id IS NOT NULL
                UNION SELECT residual.receipt_id,residual.issue_line_id,residual.transit_identity_id FROM inventory_material_residual residual
                    JOIN inventory_material_residual_ack ack ON ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id,request,context
                    WHERE residual.tenant_id=request.tenant AND residual.work_order_id=context.work_order AND residual.purpose='HANDOVER'),
            live_sources AS (SELECT DISTINCT ON (position.stock_identity_id) position.stock_identity_id id,position.quantity_base,
                position.base_unit,segment.revision,position.serial_number,lot.code lot_code,location.id location_id,location.code location_code,
                location.name location_name,receipt.id receipt_id,receipt.issue_id,document.code issue_code,receipt.issue_line_id,
                receipt.plan_id,receipt.plan_line,receipt.sku,latest.id usage_id,
                position.stock_identity_id=receipt.accepted_identity_id AND receipt.receiver_id=(SELECT actor FROM context) AND latest.id IS NULL initial_use_source
                FROM sources JOIN receipts receipt ON receipt.id=sources.receipt_id AND receipt.issue_line_id=sources.issue_line_id
                JOIN scoped_positions position ON position.stock_identity_id=sources.identity_id
                JOIN inventory_segment segment ON segment.tenant_id=position.tenant_id AND segment.id=position.stock_identity_id
                JOIN visible_locations location ON location.id=position.location_id
                JOIN inventory_document document ON document.tenant_id=position.tenant_id AND document.id=receipt.issue_id
                LEFT JOIN inventory_lot lot ON lot.tenant_id=position.tenant_id AND lot.id=position.lot_id
                LEFT JOIN LATERAL (SELECT usage.id FROM inventory_material_usage_line line JOIN inventory_usage_snapshot usage
                    ON usage.tenant_id=line.tenant_id AND usage.id=line.usage_id WHERE line.tenant_id=position.tenant_id
                    AND line.receipt_id=receipt.id AND line.issue_line_id=receipt.issue_line_id ORDER BY usage.use_revision DESC LIMIT 1) latest ON true,context
                WHERE position.custody_owner_kind='TECHNICIAN' AND position.custody_owner_id=context.actor
                    AND position.status='ISSUED' AND position.condition='SERVICEABLE' AND position.legal_owner='ISP'
                    AND position.warehouse_admission='VERIFIED' AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
                    AND position.quantity_base>0
                ORDER BY position.stock_identity_id,receipt.recorded_at DESC,receipt.id)""" + query.page("SELECT * FROM live_sources",
            """jsonb_build_object('id',id,'receiptId',receipt_id,'issueId',issue_id,'issueCode',issue_code,'issueLineId',issue_line_id,
                'planId',plan_id,'planLineId',plan_line,'sku',sku,'sourceUsageId',usage_id,'quantityBase',quantity_base::text,
                'baseUnit',base_unit,'stockRevision',revision,'location',jsonb_build_object('id',location_id,'code',location_code,'name',location_name),
                'serial',serial_number,'lotCode',lot_code,'initialUseSource',initial_use_source)""", "issue_code"), workOrder, actor), MaterialCustodyChoice::class.java)
    }

    fun usage(workOrder: UUID, actor: UUID?, page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<MaterialUsageView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size, direction = "desc"), access)
        decode(query.result(""", context AS (SELECT ?::uuid work_order,?::uuid actor)""" + query.page(
            """SELECT snapshot.id,snapshot.use_revision,snapshot.frozen_snapshot::jsonb frozen
                FROM inventory_usage_snapshot snapshot JOIN inventory_material_usage usage ON usage.tenant_id=snapshot.tenant_id AND usage.id=snapshot.id,request,context
                WHERE snapshot.tenant_id=request.tenant AND snapshot.work_order_id=context.work_order
                    AND (context.actor IS NULL OR usage.actor_id=context.actor)
                    AND NOT EXISTS (SELECT FROM jsonb_array_elements(snapshot.frozen_snapshot::jsonb->'lines') line
                        WHERE (line->'source'->>'locationId')::uuid NOT IN (SELECT id FROM visible_locations))""",
            """jsonb_build_object('id',id,'workOrderId',frozen->'workOrderId','planId',frozen->'planId','planRevision',frozen->'planRevision',
                'useRevision',use_revision,'materialMode',frozen->'materialMode','actor',jsonb_build_object('id',frozen->'actorId','name',''),
                'evidenceReference',frozen->'evidenceReference','reason',frozen->'reason','recordedAt',frozen->'recordedAt',
                'lines',coalesce((SELECT jsonb_agg(jsonb_build_object('id',line->'id','receiptId',line->'selection'->'receiptId',
                    'issueLineId',line->'selection'->'issueLineId','sku',issued->'sku','quantityBase',line->'selection'->'quantityBase',
                    'baseUnit',line->'selection'->'baseUnit','residualBase',line->'residualBase'))
                    FROM jsonb_array_elements(frozen->'lines') line JOIN inventory_material_receipt receipt
                        ON receipt.tenant_id=(SELECT tenant FROM request) AND receipt.id=(line->'selection'->>'receiptId')::uuid
                    CROSS JOIN LATERAL jsonb_array_elements(receipt.snapshot::jsonb->'issue'->'lines') issued
                    WHERE issued->>'id'=line->'selection'->>'issueLineId'),'[]'::jsonb))""", "use_revision"), workOrder, actor), MaterialUsageView::class.java)
    }

    private fun <T : Any> decode(body: String, type: Class<T>): WarehousePage<T> {
        val value = mapper.readTree(body)
        return WarehousePage(value.path("items").asSequence().map { mapper.readValue(it.toString(), type) }.toList(),
            value.path("page").asInt(), value.path("size").asInt(), value.path("totalElements").asLong())
    }
}
