package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.MaterialHandoverAuthorization
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialHandoverStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun sender(context: MaterialPlanningContext, request: MaterialResidualRequest): Pair<UUID, UUID> = jdbc.execute { sql ->
        val sender = sql.query("""SELECT custody_owner_id,location_id FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=?
            AND status='ISSUED' AND condition='SERVICEABLE' AND custody_owner_kind='TECHNICIAN' AND quantity_base>=? FOR SHARE""",
            sql.tenant, request.stockIdentityId, request.quantityBase.toLong()) { it.uuid("custody_owner_id") to it.uuid("location_id") }.singleOrNull()
            ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        sql.value("SELECT warehouse_assert_residual_source(?,?,?,?,?,?,?)", sql.tenant, context.workOrderId, request.receiptId,
            request.issueLineId, request.usageId, request.stockIdentityId, sender.first)
        sender
    }

    fun record(context: MaterialPlanningContext, authorization: MaterialHandoverAuthorization, key: String, hash: String): WarehouseOperationReceipt = jdbc.execute { sql ->
        val body = mapper.writeValueAsString(authorization)
        sql.update("""INSERT INTO inventory_material_handover(id,tenant_id,work_order_id,work_order_revision,source_identity_id,sender_id,
            receiver_id,dispatcher_id,target_location_id,quantity_base,base_unit,operation_key,payload_hash,body,cutover_epoch,recorded_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", authorization.authorizationId, sql.tenant, context.workOrderId,
            context.workOrderRevision, authorization.request.stockIdentityId, authorization.senderId, authorization.receiverId, authorization.dispatcherId,
            authorization.request.targetLocationId, authorization.request.quantityBase.toLong(), authorization.request.baseUnit,
            key, hash, body, context.cutover.snapshot.epoch, authorization.recordedAt)
        WarehouseOperationReceipt(authorization.authorizationId, context.workOrderId, context.workOrderRevision, 200, body, authorization.recordedAt)
    }

    fun get(id: UUID): MaterialHandoverAuthorization = jdbc.execute { sql ->
        mapper.readValue(sql.value("SELECT body FROM inventory_material_handover WHERE tenant_id=? AND id=? FOR SHARE", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND), MaterialHandoverAuthorization::class.java)
    }

    fun replay(context: MaterialPlanningContext, key: String, hash: String): WarehouseOperationReceipt? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|handover|$key")
        sql.query("SELECT * FROM inventory_material_handover WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("dispatcher_id") != context.authority.identity.userId) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.uuid("work_order_id") != context.workOrderId || it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (it.getLong("cutover_epoch") != context.cutover.snapshot.epoch) sql.fail(WarehouseErrorCode.STALE_CUTOVER)
            WarehouseOperationReceipt(it.uuid("id"), context.workOrderId, it.getLong("work_order_revision"), 200, it.getString("body"), it.getTimestamp("recorded_at").toInstant())
        }.singleOrNull()
    }
}
