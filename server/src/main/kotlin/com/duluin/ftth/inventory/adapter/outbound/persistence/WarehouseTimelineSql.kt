package com.duluin.ftth.inventory.adapter.outbound.persistence

internal fun warehouseTimeline(query: WarehouseQuerySql): String {
    val rows = "SELECT event.* FROM events event,request WHERE ${WarehouseQueryPredicates.eventHistory}"
    return """, events AS (
        SELECT leg.id::text id,movement.server_received_at created_at,leg.sku_id,leg.location_id,leg.status,leg.condition,leg.legal_owner,
            jsonb_build_object('id',leg.id,'kind','MOVEMENT_LEG','postingId',movement.id,'movementKind',movement.kind,
                'documentId',document.id,'documentCode',document.code,'documentRevision',movement.document_revision,
                'lineId',leg.document_line_id,'operationId',movement.operation_id,'compensatesPostingId',movement.compensates_movement_id,
                'recordedAt',${queryTime("movement.server_received_at")},'direction',leg.direction,'stockIdentityId',leg.stock_identity_id,
                'locationId',leg.location_id,'custodianId',leg.custody_owner_id,'custodianKind',leg.custody_owner_kind,
                'condition',leg.condition,'legalOwner',leg.legal_owner,'status',leg.status,'quantity',${queryQuantity("leg.quantity_base", "leg.base_unit")},
                'customerLabelSnapshot',document.customer_label_snapshot,'workOrderCodeSnapshot',document.work_order_code_snapshot) body
        FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        JOIN inventory_document document ON document.tenant_id=movement.tenant_id AND document.id=movement.document_id,request
        WHERE leg.tenant_id=request.tenant AND leg.warehouse_admission='VERIFIED' AND leg.stock_identity_id IN (SELECT id FROM target_segments)
            AND leg.location_id IN (SELECT id FROM visible_locations)
        UNION ALL
        SELECT disposition.id::text,inspection.created_at,segment.sku_id,line.location_id,'QUARANTINE','QUARANTINE',line.legal_owner,
            jsonb_build_object('id',disposition.id,'kind','INSPECTION','inspectionId',inspection.id,'operationId',inspection.operation_id,
                'documentId',line.document_id,'lineId',line.id,'recordedAt',${queryTime("inspection.created_at")},
                'stockIdentityId',disposition.segment_id,'sourceStockIdentityId',disposition.source_segment_id,'disposition',disposition.disposition,
                'quantity',${queryQuantity("disposition.quantity_base", "disposition.base_unit")})
        FROM inventory_receipt_disposition disposition JOIN inventory_inspection inspection ON inspection.tenant_id=disposition.tenant_id AND inspection.id=disposition.inspection_id
        JOIN inventory_document_line line ON line.tenant_id=inspection.tenant_id AND line.id=inspection.document_line_id
        JOIN inventory_segment segment ON segment.tenant_id=disposition.tenant_id AND segment.id=disposition.segment_id,request
        WHERE disposition.tenant_id=request.tenant AND disposition.segment_id IN (SELECT id FROM target_segments)
            AND line.location_id IN (SELECT id FROM visible_locations)
            AND (line.destination_location_id IS NULL OR line.destination_location_id IN (SELECT id FROM visible_locations))
        UNION ALL
        SELECT event.id::text||':'||(reservation->>'id'),event.recorded_at,(reservation->'dimension'->>'skuId')::uuid,
            (reservation->'dimension'->>'locationId')::uuid,reservation->>'state',reservation->'dimension'->>'condition',reservation->'dimension'->>'legalOwner',
            jsonb_build_object('id',event.id::text||':'||(reservation->>'id'),'kind','RESERVATION','eventId',event.id,'eventKind',event.event_kind,
                'reservationId',reservation->>'id','documentId',event.document_id,'documentRevision',event.document_revision,'operationId',event.operation_id,
                'recordedAt',${queryTime("event.recorded_at")},'stockIdentityId',reservation->'dimension'->>'stockIdentityId',
                'reservedUnpickedBase',reservation->'unpicked'->>'quantityBase','reservedPickedBase',reservation->'picked'->>'quantityBase',
                'baseUnit',reservation->'unpicked'->>'unit','state',reservation->>'state')
        FROM inventory_outbox event CROSS JOIN LATERAL jsonb_array_elements(coalesce(
            CASE WHEN jsonb_exists(event.payload::jsonb,'posting') THEN (event.payload::jsonb->>'posting')::jsonb ELSE event.payload::jsonb END->'reservations','[]'::jsonb)) reservation,request
        WHERE event.tenant_id=request.tenant AND reservation->'dimension'->>'stockIdentityId' IN (SELECT id::text FROM target_segments)
            AND reservation->'dimension'->>'locationId' IN (SELECT id::text FROM visible_locations)
        UNION ALL
        SELECT fact.id::text,fact.recorded_at,leg.sku_id,leg.location_id,leg.status,leg.condition,leg.legal_owner,
            jsonb_build_object('id',fact.id,'kind','MATERIAL_FACT','postingId',fact.posting_id,'recordedAt',${queryTime("fact.recorded_at")},
                'stockIdentityId',fact.stock_identity_id,'installed',fact.installed,'returned',fact.returned,'useRevision',fact.use_revision,
                'compensationId',fact.compensation_id,'quantity',${queryQuantity("fact.quantity_base", "fact.base_unit")})
        FROM inventory_customer_material_fact fact JOIN inventory_movement_leg leg ON leg.tenant_id=fact.tenant_id AND leg.movement_id=fact.posting_id
            AND leg.stock_identity_id=fact.stock_identity_id AND leg.direction='IN' AND leg.quantity_base=fact.quantity_base,request
        WHERE fact.tenant_id=request.tenant AND fact.warehouse_admission='VERIFIED' AND fact.stock_identity_id IN (SELECT id FROM target_segments)
            AND leg.location_id IN (SELECT id FROM visible_locations))
        """ + query.page(rows, "body", if (query.filter.sort == "id") "id" else "created_at", requireTarget = true)
}
