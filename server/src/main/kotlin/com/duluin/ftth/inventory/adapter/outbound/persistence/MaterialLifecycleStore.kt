package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class MaterialLifecycleStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun hasMaterials(workOrder: UUID): Boolean = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_material_plan WHERE tenant_id=? AND work_order_id=? LIMIT 1", sql.tenant, workOrder) != null
    }

    fun lock(workOrder: UUID) = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_document WHERE tenant_id=? AND work_order_id=? ORDER BY id FOR NO KEY UPDATE", sql.tenant, workOrder) { it.uuid("id") }
    }

    fun custodyScope(workOrder: UUID, actor: UUID): List<Pair<UUID, UUID>> = jdbc.execute { sql ->
        sql.query("""SELECT line.issue_line_id,(entry->'accepted'->>'locationId')::uuid location_id
            FROM inventory_material_receipt receipt JOIN inventory_material_receipt_line line
                ON line.tenant_id=receipt.tenant_id AND line.receipt_id=receipt.id
            JOIN inventory_document issue ON issue.tenant_id=receipt.tenant_id AND issue.id=receipt.issue_id
            CROSS JOIN LATERAL jsonb_array_elements(receipt.snapshot::jsonb->'lines') entry
            WHERE receipt.tenant_id=? AND issue.work_order_id=? AND receipt.receiver_id=? AND line.accepted_base>0
                AND entry->'selection'->>'issueLineId'=line.issue_line_id::text
            UNION SELECT residual.issue_line_id,residual.target_location_id FROM inventory_material_residual residual
            JOIN inventory_material_residual_ack ack ON ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id
            WHERE residual.tenant_id=? AND residual.work_order_id=? AND residual.purpose='HANDOVER' AND ack.actor_id=?""",
            sql.tenant, workOrder, actor, sql.tenant, workOrder, actor) { it.uuid("issue_line_id") to it.uuid("location_id") }
    }

    fun summary(workOrder: UUID): MaterialObligations = jdbc.execute { sql ->
        val lines = sql.query("SELECT * FROM warehouse_material_obligation_totals(?,?)", sql.tenant, workOrder) {
            MaterialObligationLine(it.uuid("issue_line_id"), it.uuid("stock_identity_id"), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                it.getString("issued_base"), it.getString("used_base"), it.getString("returned_base"), it.getString("transferred_base"),
                "0", it.getString("accountable_base"), it.getString("transit_base"), it.getString("acknowledged_base"))
        }
        val totals = sql.query("""SELECT coalesce(sum(reservation.reserved_unpicked_base),0) unpicked,
            coalesce(sum(reservation.reserved_picked_base),0) picked FROM inventory_reservation reservation
            JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE reservation.tenant_id=? AND document.work_order_id=?""", sql.tenant, workOrder) {
            it.getString("unpicked") to it.getString("picked")
        }.single()
        val latest = sql.query("SELECT revision,material_state,due_at FROM inventory_material_lifecycle WHERE tenant_id=? AND work_order_id=? ORDER BY revision DESC LIMIT 1",
            sql.tenant, workOrder) { Triple(it.getLong("revision"), it.getString("material_state"), it.getTimestamp("due_at").toInstant()) }.singleOrNull()
        val due = latest?.third ?: sql.query("SELECT min(due_at) FROM inventory_material_obligation WHERE tenant_id=? AND work_order_id=?", sql.tenant, workOrder) {
            it.getTimestamp(1)?.toInstant()
        }.single()
        val outstanding = lines.fold(0L) { total, line -> Math.addExact(total, Math.addExact(line.stillAccountableBase.toLong(), line.returnedBase.toLong())) }
        val state = when {
            latest?.second == "CLOSED" -> ResidualSettlementState.CLOSED
            outstanding > 0 && due != null && due < now() -> ResidualSettlementState.OVERDUE
            sql.value("SELECT id FROM inventory_material_residual WHERE tenant_id=? AND work_order_id=? LIMIT 1", sql.tenant, workOrder) != null -> ResidualSettlementState.SETTLING
            else -> ResidualSettlementState.OPEN
        }
        MaterialObligations(workOrder, latest?.first ?: 0, state, due, outstanding.toString(), totals.first, totals.second, lines)
    }

    fun now(): Instant = jdbc.execute { sql -> sql.query("SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }.single() }

    fun record(context: MaterialPlanningContext, action: MaterialLifecycleAction, key: String, hash: String): WarehouseOperationReceipt = jdbc.execute { sql ->
        val before = summary(context.workOrderId)
        val id = UUID.randomUUID()
        val time = now()
        val result = before.copy(revision = before.revision + 1, materialState = if (action == MaterialLifecycleAction.CLOSE) ResidualSettlementState.CLOSED
            else if (before.materialState == ResidualSettlementState.CLOSED) ResidualSettlementState.OPEN else before.materialState,
            dueAt = before.dueAt ?: time.plusSeconds(86400))
        val body = mapper.writeValueAsString(result)
        sql.update("""INSERT INTO inventory_material_lifecycle(id,tenant_id,work_order_id,revision,previous_id,actor_id,
            work_order_revision,action,material_state,operation_key,payload_hash,body,cutover_epoch,due_at,recorded_at)
            VALUES (?,?,?,?,(SELECT id FROM inventory_material_lifecycle WHERE tenant_id=? AND work_order_id=? ORDER BY revision DESC LIMIT 1),?,?,?,?,?,?,?,?,?,?)""",
            id, sql.tenant, context.workOrderId, result.revision, sql.tenant, context.workOrderId, context.authority.identity.userId,
            context.workOrderRevision, action, result.materialState, key, hash, body, context.cutover.snapshot.epoch, result.dueAt, time)
        before.lines.forEach { line ->
            sql.update("""INSERT INTO inventory_material_obligation_snapshot(id,tenant_id,lifecycle_id,issue_line_id,stock_identity_id,base_unit,
                issued_base,used_base,returned_base,transferred_base,disposed_base,accountable_base,transit_base,acknowledged_base)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant, id, line.issueLineId, line.stockIdentityId, line.baseUnit,
                line.issuedBase.toLong(), line.usedBase.toLong(), line.returnedBase.toLong(), line.transferredBase.toLong(), 0,
                line.stillAccountableBase.toLong(), line.transitBase.toLong(), line.acknowledgedBase.toLong())
        }
        WarehouseOperationReceipt(id, context.workOrderId, result.revision, 200, body, time)
    }

    fun replay(context: MaterialPlanningContext, key: String, hash: String): WarehouseOperationReceipt? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|material-close|$key")
        sql.query("SELECT * FROM inventory_material_lifecycle WHERE tenant_id=? AND action='CLOSE' AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != context.authority.identity.userId) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.uuid("work_order_id") != context.workOrderId || it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (it.getLong("cutover_epoch") != context.cutover.snapshot.epoch) sql.fail(WarehouseErrorCode.STALE_CUTOVER)
            WarehouseOperationReceipt(it.uuid("id"), context.workOrderId, it.getLong("revision"), 200, it.getString("body"), it.getTimestamp("recorded_at").toInstant())
        }.singleOrNull()
    }

    fun releaseUnpicked(context: MaterialPlanningContext, lifecycle: UUID) = jdbc.execute { sql ->
        val rows = sql.query("""SELECT reservation.* FROM inventory_reservation reservation JOIN inventory_document_line line
            ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id JOIN inventory_document document
            ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE reservation.tenant_id=? AND document.work_order_id=? AND reservation.state='OPEN'
            ORDER BY reservation.stock_identity_id,reservation.id FOR UPDATE OF reservation""", sql.tenant, context.workOrderId) {
            Triple(it.uuid("id"), it.getLong("revision"), it.getLong("reserved_unpicked_base"))
        }
        rows.filter { it.third > 0 }.forEach { row ->
            sql.update("""INSERT INTO inventory_material_cancel_release(id,tenant_id,lifecycle_id,reservation_id,source_revision,quantity_base,stock_identity_id)
                SELECT ?,tenant_id,?,id,revision,reserved_unpicked_base,stock_identity_id FROM inventory_reservation WHERE tenant_id=? AND id=?""",
                UUID.randomUUID(), lifecycle, sql.tenant, row.first)
            sql.update("UPDATE inventory_reservation SET reserved_unpicked_base=0,state='RELEASED',revision=revision+1 WHERE tenant_id=? AND id=? AND revision=? AND reserved_picked_base=0",
                sql.tenant, row.first, row.second)
        }
    }
}
