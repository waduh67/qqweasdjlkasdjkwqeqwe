package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class MaterialPlanningStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun now(): Instant = jdbc.execute { sql -> sql.query("SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }.single() }
    fun current(workOrder: UUID): MaterialPlanHistory? = jdbc.execute { sql ->
        sql.query("""SELECT plan.id binding_plan_id,snapshot.snapshot,plan.state,submission.document_id FROM inventory_material_plan plan
            LEFT JOIN inventory_material_plan_snapshot snapshot ON snapshot.tenant_id=plan.tenant_id AND snapshot.id=plan.id
            LEFT JOIN inventory_material_submission submission ON submission.tenant_id=plan.tenant_id AND submission.id=plan.id
            WHERE plan.tenant_id=? AND plan.work_order_id=? ORDER BY plan.plan_revision DESC LIMIT 1""", sql.tenant, workOrder) {
            sql.value("SELECT warehouse_assert_material_submission(?,?)", sql.tenant, it.uuid("binding_plan_id"))
            val snapshot = it.getString("snapshot") ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            MaterialPlanHistory(mapper.readValue(snapshot, MaterialPlanSnapshot::class.java), it.getString("state"), it.optionalUuid("document_id"))
        }.singleOrNull()
    }
    fun history(workOrder: UUID, page: WarehousePageRequest): WarehousePage<MaterialPlanHistory> = jdbc.execute { sql ->
        val total = sql.value("SELECT count(*) FROM inventory_material_plan WHERE tenant_id=? AND work_order_id=?", sql.tenant, workOrder)!!.toLong()
        val rows = sql.query("""SELECT plan.id binding_plan_id,snapshot.snapshot,plan.state,submission.document_id FROM inventory_material_plan plan
            LEFT JOIN inventory_material_plan_snapshot snapshot ON snapshot.tenant_id=plan.tenant_id AND snapshot.id=plan.id
            LEFT JOIN inventory_material_submission submission ON submission.tenant_id=plan.tenant_id AND submission.id=plan.id
            WHERE plan.tenant_id=? AND plan.work_order_id=? ORDER BY plan.plan_revision DESC,plan.id LIMIT ? OFFSET ?""",
            sql.tenant, workOrder, page.size, page.page.toLong() * page.size) {
            sql.value("SELECT warehouse_assert_material_submission(?,?)", sql.tenant, it.uuid("binding_plan_id"))
            val snapshot = it.getString("snapshot") ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            MaterialPlanHistory(mapper.readValue(snapshot, MaterialPlanSnapshot::class.java), it.getString("state"), it.optionalUuid("document_id"))
        }
        WarehousePage(rows, page.page, page.size, total)
    }
    fun insert(plan: MaterialPlanSnapshot) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id,reason)
            VALUES (?,?,?,?,?,?,?,?)""", plan.id, sql.tenant, plan.workOrderId, plan.planRevision, plan.workOrderRevision, plan.materialMode,
            plan.actorId, plan.reason)
        plan.lines.forEach { line -> sql.update("""INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit,continuous_cut)
            VALUES (?,?,?,?,?,?,?,?)""", line.id, sql.tenant, plan.id, line.lineNumber, line.sku.id, line.quantityBase.toLong(), line.sku.baseUnit, line.continuousCut) }
        sql.update("INSERT INTO inventory_material_plan_snapshot(id,tenant_id,template_id,snapshot) VALUES (?,?,?,?)",
            plan.id, sql.tenant, plan.templateId, mapper.writeValueAsString(plan))
    }
    fun assertReplaceable(workOrder: UUID) = jdbc.execute { sql ->
        if (sql.value("""SELECT reservation.id FROM inventory_reservation reservation
            JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE document.tenant_id=? AND document.work_order_id=? AND reservation.state='OPEN' LIMIT 1""", sql.tenant, workOrder) != null)
            sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        assertNoPhysicalFacts(workOrder)
    }
    fun assertNoPhysicalFacts(workOrder: UUID) = jdbc.execute { sql ->
        if (sql.value("""SELECT id FROM inventory_document WHERE tenant_id=? AND work_order_id=? AND kind='ISSUE' AND state NOT IN ('DRAFT','PICKED','UNPICKED') LIMIT 1""",
                sql.tenant, workOrder) != null ||
            sql.value("SELECT id FROM inventory_usage_snapshot WHERE tenant_id=? AND work_order_id=? LIMIT 1", sql.tenant, workOrder) != null ||
            sql.value("SELECT id FROM inventory_customer_material_fact WHERE tenant_id=? AND work_order_id=? LIMIT 1", sql.tenant, workOrder) != null)
            sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    fun submit(plan: MaterialPlanSnapshot, context: MaterialPlanningContext): UUID? = jdbc.execute { sql ->
        val document = if (plan.materialMode == MaterialMode.MATERIAL_REQUIRED) UUID.randomUUID() else null
        if (document != null) {
            sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,work_order_revision,
                plan_revision,work_order_code_snapshot,submitted_at,cutover_epoch,authority_epoch)
                VALUES (?,?,?,'DEMAND',?,?,?,?,?,?,clock_timestamp(),?,?)""", document, sql.tenant, "MAT-$document", context.authority.identity.userId,
                plan.workOrderId, plan.customerId, plan.workOrderRevision, plan.planRevision, plan.workOrderCode,
                context.cutover.snapshot.epoch, context.authority.epoch)
            plan.lines.forEach { line -> sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,
                sku_id,quantity_base,base_unit,tracking,continuous_cut) VALUES (?,?,?,?,0,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant, document,
                line.lineNumber, line.sku.id, line.quantityBase.toLong(), line.sku.baseUnit, line.sku.tracking, line.continuousCut) }
            sql.update("UPDATE inventory_document SET state='SUBMITTED',revision=revision+1 WHERE tenant_id=? AND id=?", sql.tenant, document)
        }
        sql.update("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=clock_timestamp(),revision=revision+1 WHERE tenant_id=? AND id=? AND state='DRAFT'", sql.tenant, plan.id)
        sql.update("INSERT INTO inventory_material_submission(id,tenant_id,document_id) VALUES (?,?,?)", plan.id, sql.tenant, document)
        document
    }
    fun documents(workOrder: UUID): Set<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_document WHERE tenant_id=? AND work_order_id=? ORDER BY id", sql.tenant, workOrder) { it.uuid("id") }.toSet()
    }
}
