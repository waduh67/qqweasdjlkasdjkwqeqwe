package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCommandJdbc
import com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkIntakeView
import com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkSource
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReferenceWorkIntakeStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun save(view: ReferenceWorkIntakeView, actor: UUID?, authorityEpoch: Long, epoch: Long) = jdbc.execute { sql ->
        sql.update("""INSERT INTO work_order_reference_intake(work_order_id,tenant_id,source,source_id,actor_id,authority_epoch,cutover_epoch,snapshot,base_snapshot)
            SELECT id,tenant_id,?,?,?,?,?,?::jsonb||jsonb_build_object(
                'createdAt',created_at,'scheduledAt',scheduled_at),to_jsonb(work_order)
            FROM work_order WHERE tenant_id=? AND id=?""",
            view.source, view.sourceId, actor, authorityEpoch, epoch, mapper.writeValueAsString(view), sql.tenant, view.id)
        Unit
    }
    fun get(id: UUID, lock: Boolean = false): ReferenceWorkIntakeView = jdbc.execute { sql ->
        if (lock) sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|work-intake|dispatch|$id")
        sql.value("""SELECT (snapshot||jsonb_build_object('dispatched',EXISTS(SELECT FROM work_order_reference WHERE tenant_id=? AND id=?)))::text
            FROM work_order_reference_intake WHERE tenant_id=? AND work_order_id=?""", sql.tenant, id, sql.tenant, id)
            ?.let { mapper.readValue(it, ReferenceWorkIntakeView::class.java) }
            ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Pekerjaan tidak ditemukan"))
    }
    fun list(page: Int, size: Int, scope: AuthorityScope, search: String?): WarehousePage<ReferenceWorkIntakeView> = jdbc.execute { sql ->
        val clauses = mutableListOf("intake.tenant_id=?", "NOT EXISTS(SELECT FROM work_order_reference WHERE tenant_id=intake.tenant_id AND id=intake.work_order_id)")
        val values = mutableListOf<Any?>(sql.tenant)
        if (scope is AuthorityScope.Restricted) {
            clauses += if (scope.ids.isEmpty()) "false" else "(snapshot->>'areaId')::uuid IN (${scope.ids.joinToString(",") { "?" }})"
            values.addAll(scope.ids.sortedBy(UUID::toString))
        }
        if (!search.isNullOrBlank()) { clauses += "position(lower(?) in lower(concat(snapshot->>'title',' ',snapshot->>'code')))>0"; values += search }
        val where = clauses.joinToString(" AND ")
        val total = requireNotNull(sql.value("SELECT count(*) FROM work_order_reference_intake intake WHERE $where", *values.toTypedArray())).toLong()
        val rows = sql.query("SELECT snapshot::text FROM work_order_reference_intake intake WHERE $where ORDER BY created_at DESC,work_order_id LIMIT ? OFFSET ?",
            *values.toTypedArray(), size, page.toLong() * size) { mapper.readValue(it.getString(1), ReferenceWorkIntakeView::class.java) }
        WarehousePage(rows, page, size, total)
    }
    fun lockSource(source: ReferenceWorkSource, id: UUID) = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|work-intake|$source|$id")
        Unit
    }
}
