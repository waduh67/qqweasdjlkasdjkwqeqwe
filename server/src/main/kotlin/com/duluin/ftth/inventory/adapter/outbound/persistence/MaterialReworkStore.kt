package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialReworkStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun latestUsage(workOrder: UUID): Pair<UUID, Long> = jdbc.execute { sql ->
        sql.query("SELECT id,use_revision FROM inventory_usage_snapshot WHERE tenant_id=? AND work_order_id=? ORDER BY use_revision DESC LIMIT 1",
            sql.tenant, workOrder) { it.uuid("id") to it.getLong("use_revision") }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun get(id: UUID): MaterialReworkSnapshot? = jdbc.execute { sql ->
        sql.value("SELECT body FROM inventory_material_rework WHERE tenant_id=? AND id=?", sql.tenant, id)?.let {
            sql.value("SELECT warehouse_assert_material_rework(?,?)", sql.tenant, id)
            mapper.readValue(it, MaterialReworkSnapshot::class.java)
        }
    }

    fun replay(context: MaterialPlanningContext, key: String, hash: String): WarehouseOperationReceipt? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|material-rework|$key")
        sql.query("SELECT * FROM inventory_material_rework WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            if (it.uuid("actor_id") != context.authority.identity.userId) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.uuid("work_order_id") != context.workOrderId || it.getString("payload_hash") != hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (it.getLong("cutover_epoch") != context.cutover.snapshot.epoch) sql.fail(WarehouseErrorCode.STALE_CUTOVER)
            sql.value("SELECT warehouse_assert_rework_live(?,?)", sql.tenant, it.uuid("id"))
            get(it.uuid("id"))
            WarehouseOperationReceipt(it.uuid("id"), it.uuid("id"), it.getLong("plan_revision"), 200, it.getString("body"), it.getTimestamp("recorded_at").toInstant())
        }.singleOrNull()
    }

    fun record(context: MaterialPlanningContext, snapshot: MaterialReworkSnapshot, command: Pair<String, String>): WarehouseOperationReceipt = jdbc.execute { sql ->
        val body = mapper.writeValueAsString(snapshot)
        sql.update("""INSERT INTO inventory_material_rework(id,tenant_id,work_order_id,work_order_revision,plan_revision,previous_plan_id,
            previous_plan_revision,previous_usage_id,previous_usage_revision,previous_evidence_revision,evidence_revision,reason,actor_id,
            operation_key,payload_hash,body,cutover_epoch,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            snapshot.reworkId, sql.tenant, context.workOrderId, context.workOrderRevision, snapshot.plan.planRevision,
            snapshot.previousPlan.id, snapshot.previousPlan.planRevision, snapshot.previousUsageId, snapshot.previousUsageRevision,
            snapshot.previousEvidenceRevision, snapshot.evidenceRevision, snapshot.reason, snapshot.actorId, command.first, command.second, body,
            context.cutover.snapshot.epoch, snapshot.recordedAt)
        snapshot.inheritedLines.forEach { line ->
            sql.update("INSERT INTO inventory_material_rework_inherited_line(tenant_id,rework_id,plan_line_id,snapshot) VALUES (?,?,?,?)",
                sql.tenant, snapshot.reworkId, line.id, mapper.writeValueAsString(line))
        }
        snapshot.evidence.forEach { entry ->
            sql.update("INSERT INTO inventory_material_rework_evidence(tenant_id,rework_id,revision_id,kind,source) VALUES (?,?,?,?,?)",
                sql.tenant, snapshot.reworkId, entry.revisionId, entry.kind, entry.source)
        }
        WarehouseOperationReceipt(snapshot.reworkId, snapshot.reworkId, snapshot.plan.planRevision, 200, body, snapshot.recordedAt)
    }

    fun assertLive(plan: UUID) = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_rework_live(?,?)", sql.tenant, plan)
    }

    fun assertDocumentLive(document: UUID) = jdbc.execute { sql ->
        sql.query("""SELECT rework.id FROM inventory_material_rework rework JOIN inventory_document document
            ON document.tenant_id=rework.tenant_id AND document.work_order_id=rework.work_order_id AND document.plan_revision=rework.plan_revision
            WHERE document.tenant_id=? AND document.id=?""", sql.tenant, document) { it.uuid("id") }.forEach { assertLive(it) }
    }

    fun hasUnissuedDemand(workOrder: UUID): Boolean = jdbc.execute { sql ->
        sql.value("SELECT warehouse_has_rework_demand(?,?)", sql.tenant, workOrder) == "t"
    }

    fun planIds(plan: UUID): List<UUID> = jdbc.execute { sql ->
        sql.query("""WITH RECURSIVE lineage(id) AS (SELECT ?::uuid UNION ALL SELECT rework.previous_plan_id
            FROM inventory_material_rework rework JOIN lineage ON lineage.id=rework.id WHERE rework.tenant_id=?)
            SELECT id FROM lineage""", plan, sql.tenant) { it.uuid("id") }
    }

    fun usageIds(workOrder: UUID, plans: List<UUID>): List<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_usage_snapshot WHERE tenant_id=? AND work_order_id=? AND plan_id=ANY(?) ORDER BY use_revision",
            sql.tenant, workOrder, sql.connection.createArrayOf("uuid", plans.toTypedArray())) { it.uuid("id") }
    }
}
