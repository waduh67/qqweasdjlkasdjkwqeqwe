package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/** Frozen installation details retain their original receipt's current visibility. */
@Repository
class MaterialDeploymentReadStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun get(workOrder: UUID, source: MaterialDeploymentSource, actor: UUID?, access: WarehouseQueryAccess): MaterialDeploymentView = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(size = 1), access)
        val body = query.result(query.page("""SELECT permit.id,permit.work_order_id,permit.asset_id,permit.actor_id,
                outcome.assignment_id,outcome.use_revision,assignment.started_at,issued->'sku' sku,issued->>'serial' serial
            FROM inventory_deployment_authorization permit
            JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
            JOIN inventory_deployment_result outcome ON outcome.tenant_id=permit.tenant_id AND outcome.authorization_id=permit.id
            JOIN inventory_asset_assignment assignment ON assignment.tenant_id=outcome.tenant_id AND assignment.id=outcome.assignment_id
            JOIN inventory_material_receipt receipt ON receipt.tenant_id=execution.tenant_id AND receipt.id=execution.receipt_id
            CROSS JOIN LATERAL jsonb_array_elements(receipt.snapshot::jsonb#>'{issue,lines}') issued,request
            WHERE permit.tenant_id=request.tenant AND permit.work_order_id=? AND permit.id=?
                AND (?::uuid IS NULL OR permit.actor_id=?::uuid) AND issued->>'id'=permit.issue_line_id::text
                AND (execution.source::jsonb#>>'{custody,locationId}')::uuid IN (SELECT id FROM visible_locations)
                AND warehouse_material_deployment_witness(request.tenant,permit.id)=?::jsonb""",
            """jsonb_build_object('authorizationId',id,'workOrderId',work_order_id,'assetId',asset_id,'assignmentId',assignment_id,
                'useRevision',use_revision,'sku',sku,'serial',serial,'actor',jsonb_build_object('id',actor_id,'name',''),'recordedAt',started_at)""", "id"),
            workOrder, source.authorizationId, actor, actor, mapper.writeValueAsString(source))
        val row = mapper.readTree(body).path("items").singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        mapper.readValue(row.toString(), MaterialDeploymentView::class.java)
    }
}
