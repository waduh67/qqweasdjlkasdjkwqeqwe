package com.duluin.ftth.inventory.adapter.outbound.persistence

/** Operational usage, not asset valuation: a later handover/removal/loss never charges the installation again. */
internal fun warehouseReportCosts(query: WarehouseQuerySql): String {
    val rows = """SELECT event.*,
        CASE WHEN event.cost_total_minor IS NULL THEN NULL ELSE sign(event.delta)*
            div(abs(event.delta)*event.cost_total_minor*2+event.cost_basis_quantity_base,event.cost_basis_quantity_base::numeric*2) END rounded_minor
        FROM cost_events event,request WHERE ${WarehouseQueryPredicates.eventHistory} AND ${WarehouseReportPersistence.serialFilter}"""
    val json = """jsonb_build_object('id',id,'workOrderId',work_order_id,'workOrderCode',work_order_code_snapshot,
        'postingId',movement_id,'compensatesPostingId',compensates_movement_id,'documentId',document_id,
        'documentRevision',document_revision,'lineId',document_line_id,'recordedAt',${queryTime("created_at")},
        'skuId',sku_id,'name',name,'stockIdentityId',stock_identity_id,'quantityBase',delta::text,'baseUnit',base_unit,
        'costState',CASE WHEN cost_total_minor IS NULL THEN 'UNKNOWN' ELSE 'KNOWN' END,
        'sourceTotalMinor',cost_total_minor::text,'sourceBasisQuantityBase',cost_basis_quantity_base::text,
        'currency',currency,'lineTotalMinor',rounded_minor::text,'rounding','HALF_UP')"""
    val metadata = """'scope','VISIBLE_LOCATIONS','costBasis','OPERATIONAL_USE','currencyTotals',coalesce((SELECT jsonb_agg(
        jsonb_build_object('currency',currency,'totalMinor',total::text) ORDER BY currency)
        FROM (SELECT currency,sum(rounded_minor) total FROM matches WHERE currency IS NOT NULL GROUP BY currency) totals),'[]'::jsonb),
        'unknownQuantities',coalesce((SELECT jsonb_agg(jsonb_build_object('workOrderId',work_order_id,'skuId',sku_id,
            'baseUnit',base_unit,'quantityBase',quantity::text) ORDER BY work_order_id,sku_id,base_unit)
            FROM (SELECT work_order_id,sku_id,base_unit,sum(delta) quantity FROM matches WHERE cost_total_minor IS NULL
                GROUP BY work_order_id,sku_id,base_unit) unknowns),'[]'::jsonb),"""
    return """, cost_events AS (
        SELECT ledger.id,ledger.created_at,ledger.sku_id,ledger.location_id,ledger.status,ledger.condition,ledger.legal_owner,
            ledger.work_order_id,ledger.work_order_code_snapshot,ledger.movement_id,ledger.compensates_movement_id,
            ledger.document_id,ledger.document_revision,ledger.document_line_id,ledger.name,ledger.stock_identity_id,
            ledger.delta,ledger.base_unit,
            CASE WHEN source_visible THEN ledger.cost_total_minor END cost_total_minor,
            CASE WHEN source_visible THEN ledger.cost_basis_quantity_base END cost_basis_quantity_base,
            CASE WHEN source_visible THEN ledger.currency END currency
        FROM report_ledger ledger JOIN work_order work ON work.tenant_id=ledger.tenant_id AND work.id=ledger.work_order_id
        CROSS JOIN LATERAL (SELECT (ledger.origin_location_id IS NULL OR ledger.origin_location_id IN (SELECT id FROM visible_locations))
            AND (ledger.origin_destination_id IS NULL OR ledger.origin_destination_id IN (SELECT id FROM visible_locations)) source_visible) visibility,request
        WHERE (ledger.movement_kind IN ('CONSUME','DEPLOY') OR EXISTS (SELECT FROM inventory_movement original
            WHERE original.tenant_id=request.tenant AND original.id=ledger.compensates_movement_id AND original.kind IN ('CONSUME','DEPLOY')))
        AND ledger.status IN ('CONSUMED','CUSTOMER_INSTALLED') AND (request.areas IS NULL OR work.area_id=ANY(request.areas)))""" +
        query.page(rows, json, if (query.filter.sort == "id") "id" else "created_at", metadata = metadata)
}
