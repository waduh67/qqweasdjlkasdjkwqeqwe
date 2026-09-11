package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import org.springframework.stereotype.Repository
import java.util.UUID

data class MaterialPhysicalTotals(val issued: Long, val used: Long, val returned: Long, val transferred: Long, val disposed: Long) {
    val accountable: Long get() = Math.subtractExact(issued, Math.addExact(Math.addExact(used, returned), Math.addExact(transferred, disposed)))
}

@Repository
class MaterialPhysicalTotalsStore(private val jdbc: WarehouseCommandJdbc) {
    fun useRevision(workOrder: UUID): Long = jdbc.execute { sql ->
        requireNotNull(sql.value("SELECT coalesce(max(use_revision),0) FROM inventory_usage_snapshot WHERE tenant_id=? AND work_order_id=?", sql.tenant, workOrder)).toLong()
    }
    fun forDemand(workOrder: UUID, demandLine: UUID): MaterialPhysicalTotals = jdbc.execute { sql ->
        val issues = sql.query("""SELECT issue.id FROM inventory_document_line issue JOIN inventory_document document
            ON document.tenant_id=issue.tenant_id AND document.id=issue.document_id
            WHERE issue.tenant_id=? AND issue.source_line_id=? AND document.work_order_id=? AND document.kind='ISSUE'
            AND document.state NOT IN ('DRAFT','PICKED','UNPICKED') ORDER BY issue.id""", sql.tenant, demandLine, workOrder) { it.uuid("id") }
        var issued = 0L
        var used = 0L
        var returned = 0L
        var transferred = 0L
        var disposed = 0L
        issues.forEach { issue ->
            val amounts = sql.query("""SELECT movement.kind,sum(leg.quantity_base::numeric) amount FROM inventory_movement movement
                JOIN inventory_movement_leg leg ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id
                JOIN inventory_document_line line ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id
                WHERE movement.tenant_id=? AND movement.state='APPLIED' AND leg.direction='IN'
                AND (line.id=? OR line.source_line_id=?) AND NOT EXISTS (
                    SELECT FROM inventory_movement_leg debit WHERE debit.tenant_id=leg.tenant_id AND debit.movement_id=leg.movement_id
                    AND debit.document_line_id=leg.document_line_id AND debit.direction='OUT' AND debit.location_id=leg.location_id
                    AND debit.custody_owner_id=leg.custody_owner_id AND debit.custody_owner_kind=leg.custody_owner_kind AND debit.status=leg.status
                    AND debit.condition=leg.condition AND debit.legal_owner=leg.legal_owner) GROUP BY movement.kind""", sql.tenant, issue, issue) {
                it.getString("kind") to it.getBigDecimal("amount").longValueExact()
            }
            if (amounts.none { it.first in setOf("ISSUE", "ISSUE_EXCEPTION") }) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            amounts.forEach { (kind, amount) -> when (kind) {
                "ISSUE", "ISSUE_EXCEPTION" -> issued = Math.addExact(issued, amount)
                "CONSUME" -> used = Math.addExact(used, amount)
                "RETURN" -> returned = Math.addExact(returned, amount)
                "TRANSFER" -> transferred = Math.addExact(transferred, amount)
                "LOSS", "SCRAP", "WRITE_OFF", "DISPOSAL" -> disposed = Math.addExact(disposed, amount)
                "RESERVE", "RELEASE", "TRANSFER_RECEIPT", "RECEIVE" -> Unit
                else -> sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            } }
        }
        MaterialPhysicalTotals(issued, used, returned, transferred, disposed).also {
            if (it.accountable < 0) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
    }
    fun assertBound(workOrder: UUID) = jdbc.execute { sql ->
        if (sql.value("""SELECT fact.id FROM inventory_customer_material_fact fact WHERE fact.tenant_id=? AND fact.work_order_id=?
            AND (fact.warehouse_admission<>'VERIFIED' OR NOT EXISTS (
                SELECT FROM inventory_movement_leg leg JOIN inventory_document_line line ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id
                JOIN inventory_document_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.source_line_id
                JOIN inventory_document_line demand ON demand.tenant_id=issue.tenant_id AND demand.id=issue.source_line_id
                JOIN inventory_document source ON source.tenant_id=demand.tenant_id AND source.id=demand.document_id
                WHERE leg.tenant_id=fact.tenant_id AND leg.movement_id=fact.posting_id AND leg.stock_identity_id=fact.stock_identity_id
                    AND leg.direction='IN' AND source.kind='DEMAND' AND source.work_order_id=fact.work_order_id)) LIMIT 1""",
                sql.tenant, workOrder) != null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (sql.value("""SELECT issue.id FROM inventory_document document JOIN inventory_document_line issue
            ON issue.tenant_id=document.tenant_id AND issue.document_id=document.id
            LEFT JOIN inventory_document_line demand ON demand.tenant_id=issue.tenant_id AND demand.id=issue.source_line_id
            LEFT JOIN inventory_document source ON source.tenant_id=demand.tenant_id AND source.id=demand.document_id
            WHERE document.tenant_id=? AND document.work_order_id=? AND document.kind='ISSUE' AND document.state NOT IN ('DRAFT','PICKED','UNPICKED')
            AND (source.kind IS DISTINCT FROM 'DEMAND' OR source.work_order_id IS DISTINCT FROM document.work_order_id) LIMIT 1""",
                sql.tenant, workOrder) != null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
}
