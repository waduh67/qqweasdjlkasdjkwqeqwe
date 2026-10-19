package com.karuhun.ftth.workorder.application.service

import com.karuhun.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.workorder.*
import com.karuhun.ftth.workorder.application.port.inbound.ReferenceWorkOrderView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

internal data class KpiWorkOrderChange(val action: String, val recordedAt: Instant, val snapshot: ReferenceWorkOrderView)

@Service
@Transactional(readOnly = true)
class WorkOrderKpiService(private val jdbc: TenantTransactionJdbc): WorkOrderKpiApi {
    private val mapper = jacksonObjectMapper()
    override fun monthlyFacts(month: YearMonth, now: Instant): WorkOrderKpiFacts {
        val tenant = TenantContext.tenantId()
        var facts: WorkOrderKpiFacts? = null
        jdbc.withinTenant(tenant) { connection ->
        val end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant()
        val changes = connection.rows("""SELECT action,created_at,snapshot::text FROM work_order_reference_command
            WHERE tenant_id=? AND resource_kind='WO' AND action<>'UPDATE' AND created_at<?
            ORDER BY resource_id,revision""", tenant, Timestamp.from(minOf(end, now.plusNanos(1)))) {
            KpiWorkOrderChange(it.getString(1), it.getTimestamp(2).toInstant(), mapper.readValue(it.getString(3), ReferenceWorkOrderView::class.java))
        }
        val completed = connection.rows("""SELECT c.work_order_id,c.technician_id,c.completed_at,w.snapshot::text
            FROM work_order_reference_completion c JOIN work_order_reference w ON w.tenant_id=c.tenant_id AND w.id=c.work_order_id
            WHERE c.tenant_id=? AND c.completed_at>=? AND c.completed_at<?""", tenant,
            Timestamp.from(month.atDay(1).atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant()), Timestamp.from(end)) {
            val snapshot = mapper.readValue(it.getString(4), ReferenceWorkOrderView::class.java)
            KpiCompletedWorkOrder(it.getObject("work_order_id", UUID::class.java), snapshot.code, snapshot.title, it.getObject("technician_id", UUID::class.java),
                it.getTimestamp(3).toInstant(), snapshot.type.kpiCategory.name, snapshot.type.kpiCategory.points)
        }
        val overdueDays = connection.rows("SELECT (snapshot->>'overdueDays')::integer FROM inventory_reference_settings WHERE tenant_id=?", tenant) { it.getInt(1) }.firstOrNull() ?: 3
        facts = WorkOrderKpiFacts(overdueWorkOrders(changes, month, overdueDays, now), completed)
        }
        return requireNotNull(facts)
    }
}

private fun <T> Connection.rows(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { statement ->
    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
}

internal fun overdueWorkOrders(changes: List<KpiWorkOrderChange>, month: YearMonth, overdueDays: Int, now: Instant): List<KpiLateWorkOrder> {
    val zone = ZoneId.of("Asia/Jakarta")
    val start = month.atDay(1).atStartOfDay(zone).toInstant()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    val result = linkedMapOf<Pair<UUID, UUID>, KpiLateWorkOrder>()
    changes.filter { it.action != "UPDATE" }.groupBy { it.snapshot.id }.values.forEach { history ->
        val ordered = history.sortedBy { it.snapshot.revision }
        ordered.forEachIndexed { index, change ->
            val order = change.snapshot
            val until = minOf(ordered.getOrNull(index + 1)?.recordedAt ?: now, now)
            val due = order.lastActivityAt.plusSeconds(overdueDays.toLong() * 86400)
            if (order.state.name == "PENDING" && due <= until && due < end && until > start) {
                result.putIfAbsent(order.id to order.technicianId, KpiLateWorkOrder(order.id, order.code, order.title, order.technicianId, due))
            }
        }
    }
    return result.values.toList()
}
