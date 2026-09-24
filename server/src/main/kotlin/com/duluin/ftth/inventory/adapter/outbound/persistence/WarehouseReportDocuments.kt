package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseReportDocuments(private val jdbc: WarehouseCommandJdbc) {
    fun print(id: UUID, revision: Long, access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(), access)
        query.result(""", target AS (SELECT document.*,operation.id operation_id,operation.original_body::jsonb response,
                operation.created_at recorded_at,identity.canonical_payload::jsonb receipt_snapshot,issue.snapshot::jsonb issue_snapshot
            FROM inventory_document document JOIN inventory_operation operation
                ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id AND operation.document_revision=?
            LEFT JOIN inventory_command_identity identity ON identity.tenant_id=operation.tenant_id AND identity.id=operation.id
            LEFT JOIN inventory_issue_snapshot issue ON issue.tenant_id=document.tenant_id AND issue.id=document.id,request
            WHERE document.tenant_id=request.tenant AND document.id=? AND document.kind IN ('RECEIPT','ISSUE','RETURN')
            AND document.state<>'DRAFT' AND operation.original_body::jsonb->>'state'<>'DRAFT' AND jsonb_exists(operation.original_body::jsonb,'state')
            AND EXISTS (SELECT FROM inventory_document_line line WHERE line.tenant_id=request.tenant AND line.document_id=document.id)
            AND NOT EXISTS (SELECT FROM inventory_document_line line WHERE line.tenant_id=request.tenant AND line.document_id=document.id
                AND ((line.location_id IS NOT NULL AND line.location_id NOT IN (SELECT id FROM visible_locations))
                    OR (line.destination_location_id IS NOT NULL AND line.destination_location_id NOT IN (SELECT id FROM visible_locations))))
            AND NOT EXISTS (SELECT FROM inventory_movement movement JOIN inventory_movement_leg leg
                ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id WHERE movement.tenant_id=request.tenant
                AND movement.document_id=document.id AND movement.document_revision<=operation.document_revision
                AND leg.location_id NOT IN (SELECT id FROM visible_locations))
            AND (document.work_order_id IS NULL OR EXISTS (SELECT FROM work_order work WHERE work.tenant_id=request.tenant
                AND work.id=document.work_order_id AND (request.areas IS NULL OR work.area_id=ANY(request.areas))))),
        print_lines AS (SELECT line.id,line.line_number,line.sku_id,line.base_unit,line.quantity_base,line.stock_identity_id,line.location_id,
                coalesce(receipt_line->'sku',issue_line->'sku',origin_receipt_line->'sku') sku_snapshot,
                coalesce(receipt_line->>'serial',issue_line->>'serial',asset.serial_number) serial_number,
                CASE WHEN target.kind='RECEIPT' THEN line.cost_total_minor ELSE original.cost_total_minor END cost_total_minor,
                CASE WHEN target.kind='RECEIPT' THEN line.cost_basis_quantity_base ELSE original.cost_basis_quantity_base END cost_basis_quantity_base,
                CASE WHEN target.kind='RECEIPT' THEN line.currency ELSE original.currency END currency
            FROM target JOIN inventory_document_line line ON line.tenant_id=target.tenant_id AND line.document_id=target.id
            LEFT JOIN inventory_segment segment ON segment.tenant_id=line.tenant_id AND segment.id=line.stock_identity_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
            LEFT JOIN inventory_document_line original ON original.tenant_id=line.tenant_id
                AND original.id=coalesce(asset.origin_document_line_id,lot.origin_document_line_id)
                AND (original.location_id IS NULL OR original.location_id IN (SELECT id FROM visible_locations))
                AND (original.destination_location_id IS NULL OR original.destination_location_id IN (SELECT id FROM visible_locations))
            LEFT JOIN inventory_receipt_intake origin_receipt ON origin_receipt.tenant_id=original.tenant_id AND origin_receipt.id=original.document_id
            LEFT JOIN LATERAL jsonb_array_elements(CASE WHEN target.kind='RECEIPT' THEN target.receipt_snapshot->'lines' ELSE '[]'::jsonb END) receipt_line
                ON receipt_line->>'id'=line.id::text
            LEFT JOIN LATERAL jsonb_array_elements(target.issue_snapshot->'lines') issue_line ON issue_line->>'id'=line.id::text
            LEFT JOIN LATERAL jsonb_array_elements(origin_receipt.snapshot::jsonb->'lines') origin_receipt_line
                ON origin_receipt_line->>'id'=original.id::text)
        SELECT jsonb_build_object('documentId',target.id,'documentCode',target.code,'kind',target.kind,'documentRevision',?,
            'state',target.response->>'state','operationId',target.operation_id,'recordedAt',${queryTime("target.recorded_at")},
            'sourceDocumentId',target.source_document_id,'sourceRevision',target.source_revision,
            'workOrderId',target.work_order_id,'workOrderCode',target.work_order_code_snapshot,
            'supplier',CASE WHEN target.kind='RECEIPT' THEN jsonb_build_object('id',target.receipt_snapshot->'supplier'->>'id',
                'name',target.receipt_snapshot->'supplier'->>'name') ELSE NULL END,
            'lines',(SELECT jsonb_agg(jsonb_build_object('lineId',id,'lineNumber',line_number,'skuId',sku_id,
                'skuCode',sku_snapshot->>'code','skuName',sku_snapshot->>'name',
                'nameState',CASE WHEN sku_snapshot IS NULL THEN 'NOT_CAPTURED' ELSE 'SNAPSHOT' END,
                'stockIdentityId',stock_identity_id,'serial',serial_number,'locationId',location_id,
                'quantity',${queryQuantity("quantity_base", "base_unit")}) || ${queryCost("print_lines", access.cost)} ORDER BY line_number) FROM print_lines))::text
        FROM target""", revision, id, revision)
    }
}
