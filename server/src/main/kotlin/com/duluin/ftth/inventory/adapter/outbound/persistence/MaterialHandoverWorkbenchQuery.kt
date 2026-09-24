package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialHandoverWorkbenchQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun sources(context: MaterialPlanningContext, page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<MaterialHandoverSource> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(MaterialCustodyQuerySql.rows + query.page(
            "SELECT * FROM live_sources WHERE custodian_id<>?::uuid",
            """jsonb_build_object('id',id,'sender',jsonb_build_object('id',custodian_id,'name',''),'source',${MaterialCustodyQuerySql.body})""", "issue_code"),
            context.workOrderId, null, context.authority.identity.userId), MaterialHandoverSource::class.java)
    }
    fun targets(context: MaterialPlanningContext, page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<MaterialHandoverTarget> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(query.page("""SELECT id,code,name,custodian_id FROM visible_locations WHERE kind='TECHNICIAN' AND state='ACTIVE'
            AND custodian_id=ANY(?::uuid[]) AND custodian_id<>?::uuid""",
            """jsonb_build_object('id',id,'code',code,'name',name,'receiver',jsonb_build_object('id',custodian_id,'name',''))""", "code"),
            sql.connection.createArrayOf("uuid", context.activeAssigneeIds.toTypedArray()), context.authority.identity.userId), MaterialHandoverTarget::class.java)
    }
    fun pending(context: MaterialPlanningContext, page: WarehousePageRequest, access: WarehouseQueryAccess, id: UUID?): WarehousePage<MaterialHandoverGrant> = jdbc.execute { sql ->
        val query = query(sql, page, access)
        decode(query.result(MaterialCustodyQuerySql.rows + query.page("""SELECT handover.id,handover.work_order_id,handover.body::jsonb frozen,
            handover.sender_id,handover.receiver_id,handover.dispatcher_id,handover.recorded_at,
            source.quantity_base,source.revision,source.sku,source.serial_number,source.lot_code,
            location.id location_id,location.code location_code,location.name location_name
            FROM inventory_material_handover handover JOIN live_sources source ON source.id=handover.source_identity_id
                AND source.custodian_id=handover.sender_id AND source.receipt_id=(handover.body::jsonb->'request'->>'receiptId')::uuid
                AND source.issue_line_id=(handover.body::jsonb->'request'->>'issueLineId')::uuid
            JOIN inventory_location location ON location.tenant_id=handover.tenant_id AND location.id=handover.target_location_id,request,context
            WHERE handover.tenant_id=request.tenant AND handover.work_order_id=context.work_order AND handover.sender_id=context.actor
                AND (?::uuid IS NULL OR handover.id=?::uuid) AND location.kind='TECHNICIAN' AND location.state='ACTIVE'
                AND NOT EXISTS (SELECT FROM inventory_material_residual residual WHERE residual.tenant_id=handover.tenant_id AND residual.authorization_id=handover.id)""",
            """jsonb_build_object('id',id,'workOrderId',work_order_id,'request',frozen->'request',
                'sender',jsonb_build_object('id',sender_id,'name',''),'receiver',jsonb_build_object('id',receiver_id,'name',''),
                'dispatcher',jsonb_build_object('id',dispatcher_id,'name',''),
                'location',jsonb_build_object('id',location_id,'code',location_code,'name',location_name),
                'sku',sku,'serial',serial_number,'lotCode',lot_code,'currentQuantityBase',quantity_base::text,'stockRevision',revision,'recordedAt',${queryTime("recorded_at")})""", "recorded_at"),
            context.workOrderId, context.authority.identity.userId, id, id), MaterialHandoverGrant::class.java)
    }
    private fun query(sql: PostingSql, page: WarehousePageRequest, access: WarehouseQueryAccess) = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size), access)
    private fun <T : Any> decode(body: String, type: Class<T>): WarehousePage<T> {
        val result = mapper.readTree(body)
        return WarehousePage(result.path("items").asSequence().map { mapper.readValue(it.toString(), type) }.toList(),
            result.path("page").asInt(), result.path("size").asInt(), result.path("totalElements").asLong())
    }
}
