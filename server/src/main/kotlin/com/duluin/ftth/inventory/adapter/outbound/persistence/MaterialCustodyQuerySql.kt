package com.duluin.ftth.inventory.adapter.outbound.persistence

/** Current receipt descendants only; caller supplies a fenced actor or an authorized dispatcher scope. */
internal object MaterialCustodyQuerySql {
    val rows = """, context AS (SELECT ?::uuid work_order,?::uuid actor),
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
            live_sources AS (SELECT DISTINCT ON (position.stock_identity_id) position.stock_identity_id id,position.quantity_base,position.custody_owner_id custodian_id,
                position.base_unit,segment.revision,position.serial_number,lot.code lot_code,location.id location_id,location.code location_code,
                location.name location_name,receipt.id receipt_id,receipt.issue_id,document.code issue_code,receipt.issue_line_id,
                receipt.plan_id,receipt.plan_line,receipt.sku,latest.id usage_id,
                position.stock_identity_id=receipt.accepted_identity_id AND receipt.receiver_id=position.custody_owner_id AND latest.id IS NULL initial_use_source
                FROM sources JOIN receipts receipt ON receipt.id=sources.receipt_id AND receipt.issue_line_id=sources.issue_line_id
                JOIN scoped_positions position ON position.stock_identity_id=sources.identity_id
                JOIN inventory_segment segment ON segment.tenant_id=position.tenant_id AND segment.id=position.stock_identity_id
                JOIN visible_locations location ON location.id=position.location_id
                JOIN inventory_document document ON document.tenant_id=position.tenant_id AND document.id=receipt.issue_id
                LEFT JOIN inventory_lot lot ON lot.tenant_id=position.tenant_id AND lot.id=position.lot_id
                LEFT JOIN LATERAL (SELECT usage.id FROM inventory_material_usage_line line JOIN inventory_usage_snapshot usage
                    ON usage.tenant_id=line.tenant_id AND usage.id=line.usage_id WHERE line.tenant_id=position.tenant_id
                    AND line.receipt_id=receipt.id AND line.issue_line_id=receipt.issue_line_id ORDER BY usage.use_revision DESC LIMIT 1) latest ON true,context
                WHERE position.custody_owner_kind='TECHNICIAN' AND (context.actor IS NULL OR position.custody_owner_id=context.actor)
                    AND position.status='ISSUED' AND position.condition='SERVICEABLE' AND position.legal_owner='ISP'
                    AND position.warehouse_admission='VERIFIED' AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
                    AND position.quantity_base>0
                ORDER BY position.stock_identity_id,receipt.recorded_at DESC,receipt.id)"""
    val body = """jsonb_build_object('id',id,'receiptId',receipt_id,'issueId',issue_id,'issueCode',issue_code,'issueLineId',issue_line_id,
                'planId',plan_id,'planLineId',plan_line,'sku',sku,'sourceUsageId',usage_id,'quantityBase',quantity_base::text,
                'baseUnit',base_unit,'stockRevision',revision,'location',jsonb_build_object('id',location_id,'code',location_code,'name',location_name),
                'serial',serial_number,'lotCode',lot_code,'initialUseSource',initial_use_source)"""
}
