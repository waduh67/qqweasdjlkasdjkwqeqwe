package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseIssueState
import com.duluin.ftth.inventory.WarehousePageRequest
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import java.util.UUID

/** Current lifecycle and receipt totals alongside labels from the immutable picking slip. */
@Repository
class WarehouseIssueQueries(private val jdbc: WarehouseCommandJdbc) {
    fun list(workOrderId: UUID, page: WarehousePageRequest, state: WarehouseIssueState?, access: WarehouseQueryAccess, canOverride: Boolean): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page.page, page.size, "createdAt", "desc", status = state?.name), access)
        query.result(""", scoped_issues AS MATERIALIZED (
            SELECT document.*,snapshot.snapshot::jsonb picked_snapshot,
                coalesce((SELECT operation.original_body FROM inventory_operation operation WHERE operation.tenant_id=document.tenant_id
                    AND operation.document_id=document.id AND operation.business_action IN ('DISPATCH','UNPICK')
                    ORDER BY operation.document_revision DESC LIMIT 1),snapshot.snapshot)::jsonb slip_snapshot,
                EXISTS (SELECT FROM inventory_issue_unpick unpick WHERE unpick.tenant_id=document.tenant_id AND unpick.id=document.id) unpicked,
                EXISTS (SELECT FROM inventory_operation operation WHERE operation.tenant_id=document.tenant_id
                    AND operation.document_id=document.id AND operation.business_action='DISPATCH') dispatched
            FROM inventory_document document JOIN inventory_issue_snapshot snapshot
                ON snapshot.tenant_id=document.tenant_id AND snapshot.id=document.id,request
            WHERE document.tenant_id=request.tenant AND document.work_order_id=? AND document.kind='ISSUE'
                AND (request.status IS NULL OR document.state=request.status)
                AND EXISTS (SELECT FROM inventory_document_line line WHERE line.tenant_id=document.tenant_id AND line.document_id=document.id)
                AND NOT EXISTS (SELECT FROM inventory_document_line line WHERE line.tenant_id=document.tenant_id AND line.document_id=document.id
                    AND (line.location_id IS NULL OR line.location_id NOT IN (SELECT id FROM visible_locations)
                        OR (line.destination_location_id IS NOT NULL AND line.destination_location_id NOT IN (SELECT id FROM visible_locations))))
                AND NOT EXISTS (SELECT FROM inventory_movement movement JOIN inventory_movement_leg leg
                    ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id
                    WHERE movement.tenant_id=document.tenant_id AND movement.document_id=document.id
                        AND leg.location_id NOT IN (SELECT id FROM visible_locations))
                AND (? OR NOT EXISTS (SELECT FROM jsonb_array_elements(snapshot.snapshot::jsonb->'lines') line
                    WHERE coalesce(line->'substitution','null'::jsonb)<>'null'::jsonb OR coalesce(line->'originalSku','null'::jsonb)<>'null'::jsonb)))
        """ + query.page("SELECT * FROM scoped_issues", """jsonb_build_object(
            'id',id,'issueId',id,'code',code,'state',state,'revision',revision,'unpicked',unpicked,
            'workOrderId',work_order_id,'workOrderCode',work_order_code_snapshot,'planRevision',plan_revision,
            'sender',slip_snapshot->'sender','receiver',slip_snapshot->'receiver','createdAt',${queryTime("created_at")},
            'lines',(SELECT jsonb_agg(jsonb_build_object('issueLineId',line->>'id','planLineId',line->>'planLineId',
                'sku',line->'sku','serial',line->'serial','lotCode',line->'lotCode','locationName',line->'locationName',
                'baseUnit',line->>'baseUnit','pickedBase',CASE WHEN state='PICKED' AND NOT unpicked THEN line->>'quantityBase' ELSE '0' END,
                'dispatchedBase',CASE WHEN dispatched THEN line->>'quantityBase' ELSE '0' END,
                'acceptedBase',coalesce((SELECT sum(received.accepted_base::numeric)::text FROM inventory_material_receipt_line received
                    JOIN inventory_material_receipt receipt ON receipt.tenant_id=received.tenant_id AND receipt.id=received.receipt_id
                    WHERE received.tenant_id=matches.tenant_id AND receipt.issue_id=matches.id AND received.issue_line_id=(line->>'id')::uuid),'0'))
                ORDER BY line->>'id') FROM jsonb_array_elements(picked_snapshot->'lines') line))""", "created_at"), workOrderId, canOverride)
    }
}
