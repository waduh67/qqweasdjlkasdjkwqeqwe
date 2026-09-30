package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseDraftExpiry
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
@org.springframework.context.annotation.DependsOn("warehouseDraftSchemaGuard")
class WarehouseDraftLifetimeStore(private val jdbc: WarehouseCommandJdbc) {
    fun document(id: UUID): WarehouseDraftExpiry? = jdbc.execute { sql ->
        sql.query("""SELECT warehouse_document_draft_expired_at(?,?) deadline,
            (SELECT expired_at FROM inventory_document_draft_expiry WHERE tenant_id=? AND document_id=?) recorded_at""",
            sql.tenant, id, sql.tenant, id) { row -> row.getTimestamp("deadline")?.toInstant()?.let {
                WarehouseDraftExpiry(it, row.getTimestamp("recorded_at")?.toInstant())
            } }.single()
    }

    fun plan(id: UUID): WarehouseDraftExpiry? = jdbc.execute { sql ->
        sql.query("""SELECT warehouse_plan_draft_expired_at(?,?) deadline,
            (SELECT expired_at FROM inventory_plan_draft_expiry WHERE tenant_id=? AND plan_id=?) recorded_at""",
            sql.tenant, id, sql.tenant, id) { row -> row.getTimestamp("deadline")?.toInstant()?.let {
                WarehouseDraftExpiry(it, row.getTimestamp("recorded_at")?.toInstant())
            } }.single()
    }

    /** Call only after current authorization and successful original-response replay lookup. */
    fun assertDocumentLive(id: UUID) = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_document_draft_live(?,?)", sql.tenant, id)
        Unit
    }

    fun assertPlanLive(id: UUID) = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_plan_draft_live(?,?)", sql.tenant, id)
        Unit
    }

    fun dueDocument(): UUID? = jdbc.execute { sql ->
        sql.query("""SELECT document.id FROM inventory_document document
            WHERE document.tenant_id=? AND document.state='DRAFT' AND warehouse_has_draft_clock(document.kind)
                AND NOT EXISTS(SELECT FROM inventory_document_draft_expiry expiry
                    WHERE expiry.tenant_id=document.tenant_id AND expiry.document_id=document.id)
                AND warehouse_document_draft_expired_at(document.tenant_id,document.id) IS NOT NULL
            ORDER BY document.id LIMIT 1 FOR UPDATE OF document SKIP LOCKED""", sql.tenant) { it.uuid("id") }.singleOrNull()
    }

    fun duePlan(): UUID? = jdbc.execute { sql ->
        sql.query("""SELECT plan.id FROM inventory_material_plan plan
            WHERE plan.tenant_id=? AND plan.state='DRAFT'
                AND NOT EXISTS(SELECT FROM inventory_plan_draft_expiry expiry
                    WHERE expiry.tenant_id=plan.tenant_id AND expiry.plan_id=plan.id)
                AND warehouse_plan_draft_expired_at(plan.tenant_id,plan.id) IS NOT NULL
            ORDER BY plan.id LIMIT 1 FOR UPDATE OF plan SKIP LOCKED""", sql.tenant) { it.uuid("id") }.singleOrNull()
    }

    fun expireDocument(id: UUID): Boolean = jdbc.execute { sql ->
        sql.query("SELECT warehouse_expire_document_draft(?,?)", sql.tenant, id) { it.getBoolean(1) }.single()
    }

    fun expirePlan(id: UUID): Boolean = jdbc.execute { sql ->
        sql.query("SELECT warehouse_expire_plan_draft(?,?)", sql.tenant, id) { it.getBoolean(1) }.single()
    }

    fun pendingApprovals(id: UUID): List<UUID> = jdbc.execute { sql ->
        sql.query("""SELECT id FROM inventory_approval WHERE tenant_id=? AND source_document_id=?
            AND status='PENDING' AND evaluation_snapshot IS NOT NULL ORDER BY id FOR UPDATE""", sql.tenant, id) { it.uuid("id") }
    }
}
