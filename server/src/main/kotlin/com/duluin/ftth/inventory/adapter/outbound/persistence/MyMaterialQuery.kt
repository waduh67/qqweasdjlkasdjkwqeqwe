package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MyMaterialQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun belongs(workOrder: UUID, actor: UUID, access: WarehouseQueryAccess): Boolean =
        jobs(actor, WarehousePageRequest(0, 1), access, workOrder).totalElements > 0

    fun jobs(actor: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess, workOrder: UUID? = null): WarehousePage<MyMaterialJob> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(""", actor AS (SELECT ?::uuid id), owned AS (
            SELECT document.work_order_id,document.work_order_code_snapshot code,document.created_at
            FROM inventory_issue_snapshot issue JOIN inventory_document document ON document.tenant_id=issue.tenant_id AND document.id=issue.id,request,actor
            WHERE issue.tenant_id=request.tenant AND issue.receiver_id=actor.id AND document.state NOT IN ('DRAFT','PICKED','UNPICKED')
                AND EXISTS (SELECT FROM inventory_movement movement JOIN inventory_movement_leg leg ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id
                    WHERE movement.tenant_id=request.tenant AND movement.document_id=document.id AND leg.location_id IN (SELECT id FROM visible_locations))
            UNION ALL SELECT document.work_order_id,document.work_order_code_snapshot,residual.recorded_at
            FROM inventory_material_residual residual JOIN inventory_document document ON document.tenant_id=residual.tenant_id AND document.id=residual.id,request,actor
            WHERE residual.tenant_id=request.tenant AND ((residual.sender_id=actor.id AND (residual.source_dimension::jsonb->>'locationId')::uuid IN (SELECT id FROM visible_locations))
                OR (residual.receiver_id=actor.id AND residual.target_location_id IN (SELECT id FROM visible_locations)))
            UNION ALL SELECT rma.work_order_id,coalesce(document.work_order_code_snapshot,document.code),document.created_at
            FROM inventory_rma_handover rma JOIN inventory_document document ON document.tenant_id=rma.tenant_id AND document.id=rma.id,request,actor
            WHERE rma.tenant_id=request.tenant AND rma.technician_id=actor.id AND document.state IN ('DISPATCHED','RECEIVED')
                AND (rma.body::jsonb#>>'{view,sourceLocationId}')::uuid IN (SELECT id FROM visible_locations)
                AND (rma.body::jsonb#>>'{view,transitLocationId}')::uuid IN (SELECT id FROM visible_locations)
                AND (rma.body::jsonb#>>'{view,technicianLocationId}')::uuid IN (SELECT id FROM visible_locations))""" + query.page(
            "SELECT work_order_id id,max(code) code,max(created_at) updated_at FROM owned WHERE (?::uuid IS NULL OR work_order_id=?::uuid) GROUP BY work_order_id",
            """jsonb_build_object('id',id,'code',code,'updatedAt',${queryTime("updated_at")})""", "updated_at"), actor, workOrder, workOrder), MyMaterialJob::class.java)
    }

    /** Customer-owned repair stock has its own source chain and never enters ordinary ISP custody choices. */
    fun rmas(workOrder: UUID, actor: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess, handover: UUID?): WarehousePage<CustomerRmaHandover> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(query.page("""SELECT rma.id,document.created_at,operation.original_body::jsonb body
            FROM inventory_rma_handover rma JOIN inventory_document document ON document.tenant_id=rma.tenant_id AND document.id=rma.id
            JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id AND operation.document_revision=document.revision
            JOIN scoped_positions position ON position.tenant_id=rma.tenant_id AND position.stock_identity_id=rma.asset_id
            JOIN inventory_serialized_asset asset ON asset.tenant_id=rma.tenant_id AND asset.id=rma.asset_id
            JOIN inventory_sku sku ON sku.tenant_id=asset.tenant_id AND sku.id=asset.sku_id,request
            WHERE rma.tenant_id=request.tenant AND rma.work_order_id=? AND rma.technician_id=? AND (?::uuid IS NULL OR rma.id=?::uuid)
                AND (rma.body::jsonb#>>'{view,sourceLocationId}')::uuid IN (SELECT id FROM visible_locations)
                AND (rma.body::jsonb#>>'{view,transitLocationId}')::uuid IN (SELECT id FROM visible_locations)
                AND (rma.body::jsonb#>>'{view,technicianLocationId}')::uuid IN (SELECT id FROM visible_locations)
                AND position.custody_owner_id=rma.technician_id AND position.quantity_base=1 AND position.base_unit='EA'
                AND position.legal_owner='CUSTOMER' AND position.condition='SERVICEABLE' AND position.warehouse_admission='VERIFIED'
                AND asset.warehouse_admission='VERIFIED' AND sku.state='ACTIVE' AND sku.tracking='SERIAL' AND sku.base_unit='EA'
                AND ((document.state='DISPATCHED' AND position.status='IN_TRANSIT' AND position.custody_owner_kind='TRANSIT'
                    AND position.location_id=(rma.body::jsonb#>>'{view,transitLocationId}')::uuid)
                  OR (document.state='RECEIVED' AND position.status='ISSUED' AND position.custody_owner_kind='TECHNICIAN'
                    AND position.location_id=(rma.body::jsonb#>>'{view,technicianLocationId}')::uuid))""", "body", "created_at"),
            workOrder, actor, handover, handover), CustomerRmaHandover::class.java)
    }

    fun issues(workOrder: UUID, actor: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess, issue: UUID? = null): WarehousePage<MyMaterialIssue> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(query.page("""SELECT document.*,issue.snapshot::jsonb frozen FROM inventory_issue_snapshot issue
            JOIN inventory_document document ON document.tenant_id=issue.tenant_id AND document.id=issue.id,request
            WHERE issue.tenant_id=request.tenant AND document.work_order_id=? AND issue.receiver_id=? AND (?::uuid IS NULL OR document.id=?::uuid)
                AND document.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED')
                AND EXISTS (SELECT FROM inventory_operation operation WHERE operation.tenant_id=request.tenant AND operation.document_id=document.id AND operation.business_action='DISPATCH')
                AND NOT EXISTS (SELECT FROM inventory_operation operation JOIN inventory_movement movement ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
                    JOIN inventory_movement_leg leg ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id
                    WHERE operation.tenant_id=request.tenant AND operation.document_id=document.id AND operation.business_action='DISPATCH'
                    AND leg.direction='IN' AND leg.status='IN_TRANSIT' AND leg.location_id NOT IN (SELECT id FROM visible_locations))""",
            """jsonb_build_object('id',id,'code',code,'workOrderId',work_order_id,'workOrderRevision',frozen->'workOrderRevision',
                'revision',revision,'state',state,'sender',frozen->'sender','receiver',frozen->'receiver','lines',
                (SELECT jsonb_agg(jsonb_build_object('id',line->'id','stockIdentityId',line->'dimension'->'stockIdentityId','sku',line->'sku',
                    'baseUnit',line->'baseUnit','dispatchedBase',line->>'quantityBase','acceptedBase',accepted.amount::text,
                    'remainingBase',((line->>'quantityBase')::numeric-accepted.amount)::text,'serial',line->'serial','lotCode',line->'lotCode') ORDER BY line->>'id')
                    FROM jsonb_array_elements(frozen->'lines') line CROSS JOIN LATERAL (
                        SELECT coalesce(sum(received.accepted_base::numeric),0) amount FROM inventory_material_receipt_line received
                        WHERE received.tenant_id=matches.tenant_id AND received.issue_id=matches.id AND received.issue_line_id=(line->>'id')::uuid) accepted))""", "created_at"),
            workOrder, actor, issue, issue), MyMaterialIssue::class.java)
    }

    fun residuals(workOrder: UUID?, actor: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess, returnInbox: Boolean = false): WarehousePage<MyMaterialResidual> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(query.page("""SELECT residual.id,residual.work_order_id,residual.sender_id,residual.receiver_id,residual.target_location_id,residual.purpose,
                residual.quantity_base,residual.base_unit,residual.recorded_at,document.code,document.revision,document.state,location.code location_code,location.name location_name,
                sku.id sku_id,sku.revision sku_revision,sku.code sku_code,sku.name sku_name,sku.tracking,
                asset.serial_number,lot.code lot_code
            FROM inventory_material_residual residual JOIN inventory_document document ON document.tenant_id=residual.tenant_id AND document.id=residual.id
            JOIN inventory_document_line line ON line.tenant_id=residual.tenant_id AND line.document_id=residual.id
            JOIN inventory_sku sku ON sku.tenant_id=line.tenant_id AND sku.id=line.sku_id
            JOIN inventory_location location ON location.tenant_id=residual.tenant_id AND location.id=residual.target_location_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=line.tenant_id AND asset.id=line.stock_identity_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.id=line.lot_id,request
            WHERE residual.tenant_id=request.tenant AND (?::uuid IS NULL OR residual.work_order_id=?::uuid) AND
                CASE WHEN ?::boolean THEN residual.purpose='RETURN' AND document.state='DISPATCHED' AND residual.sender_id<>?::uuid
                    AND residual.target_location_id IN (SELECT id FROM visible_locations)
                ELSE ((residual.sender_id=? AND
                (residual.source_dimension::jsonb->>'locationId')::uuid IN (SELECT id FROM visible_locations))
                OR (residual.receiver_id=? AND residual.target_location_id IN (SELECT id FROM visible_locations))) END""",
            """jsonb_build_object('id',id,'code',code,'workOrderId',work_order_id,'revision',revision,'state',state,'purpose',purpose,
                'sender',jsonb_build_object('id',sender_id,'name',''),'receiver',CASE WHEN receiver_id IS NULL THEN NULL ELSE jsonb_build_object('id',receiver_id,'name','') END,
                'location',jsonb_build_object('id',target_location_id,'code',location_code,'name',location_name),
                'sku',jsonb_build_object('id',sku_id,'revision',sku_revision,'code',sku_code,'name',sku_name,'tracking',tracking,'baseUnit',base_unit),
                'quantityBase',quantity_base::text,'baseUnit',base_unit,'serial',serial_number,'lotCode',lot_code,'recordedAt',${queryTime("recorded_at")})""", "recorded_at"),
            workOrder, workOrder, returnInbox, actor, actor, actor), MyMaterialResidual::class.java)
    }

    fun returnLocations(page: WarehousePageRequest, access: WarehouseQueryAccess, location: UUID? = null): WarehousePage<WarehouseApprovalLocation> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(query.page("SELECT id,code,name FROM visible_locations WHERE kind='QUARANTINE' AND state='ACTIVE' AND (?::uuid IS NULL OR id=?::uuid)",
            "jsonb_build_object('id',id,'code',code,'name',name)", "code"), location, location), WarehouseApprovalLocation::class.java)
    }
    private fun query(sql: PostingSql, page: WarehousePageRequest, access: WarehouseQueryAccess) = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size, direction = "desc"), access)
    private fun <T : Any> decode(body: String, type: Class<T>): WarehousePage<T> {
        val value = mapper.readTree(body)
        return WarehousePage(value.path("items").asSequence().map { mapper.readValue(it.toString(), type) }.toList(),
            value.path("page").asInt(), value.path("size").asInt(), value.path("totalElements").asLong())
    }
}
