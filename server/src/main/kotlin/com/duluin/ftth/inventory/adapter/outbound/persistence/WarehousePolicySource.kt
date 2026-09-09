package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import java.math.BigInteger
import java.util.UUID

data class PolicySourceLine(val locationId: UUID?, val custodianId: UUID?, val quantity: BigInteger,
    val numerator: BigInteger?, val denominator: BigInteger?, val currency: String?)
data class PolicySource(val id: UUID, val revision: Long, val operation: PolicyOperation, val requesterId: UUID,
    val counters: Set<UUID>, val lines: List<PolicySourceLine>)

@Repository
class WarehousePolicySource(private val jdbc: WarehouseCommandJdbc) {
    fun lock(input: WarehouseSourceInput): PolicySource = jdbc.execute { sql ->
        val header = sql.query("SELECT kind,actor_id,revision FROM inventory_document WHERE tenant_id=? AND id=? FOR NO KEY UPDATE", sql.tenant, input.sourceDocumentId) {
            Triple(it.getString("kind"), it.uuid("actor_id"), it.getLong("revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        if (header.third != input.sourceRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
        val operation = when (header.first) {
            "RECEIPT" -> PolicyOperation.RECEIPT
            "ISSUE" -> PolicyOperation.ISSUE
            "OPENING_BALANCE" -> PolicyOperation.OPENING_BALANCE
            "ADJUSTMENT" -> PolicyOperation.ADJUSTMENT
            "LOSS" -> PolicyOperation.LOSS
            "SCRAP" -> PolicyOperation.SCRAP
            "COUNT" -> PolicyOperation.COUNT_VARIANCE
            else -> sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        val lines = sql.query("""SELECT line.location_id,line.custodian_id,line.custodian_kind,line.quantity_base,
            coalesce(line.cost_total_minor,lot.cost_total_minor) numerator,
            coalesce(line.cost_basis_quantity_base,lot.cost_basis_quantity_base) denominator,
            coalesce(line.currency,lot.currency) currency FROM inventory_document_line line
            LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.id=line.lot_id
            WHERE line.tenant_id=? AND line.document_id=? ORDER BY line.line_number""", sql.tenant, input.sourceDocumentId) {
            PolicySourceLine(it.optionalUuid("location_id"), if (it.getString("custodian_kind") == "TECHNICIAN") it.optionalUuid("custodian_id") else null,
                it.getString("quantity_base").toBigInteger(), it.getString("numerator")?.toBigInteger(),
                it.getString("denominator")?.toBigInteger(), it.getString("currency"))
        }
        if (lines.isEmpty() || lines.any { it.locationId == null }) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val counters = sql.query("SELECT counter_id FROM inventory_cycle_count WHERE tenant_id=? AND document_id=? AND counter_id IS NOT NULL",
            sql.tenant, input.sourceDocumentId) { it.uuid("counter_id") }.toSet()
        PolicySource(input.sourceDocumentId, header.third, operation, header.second, counters, lines)
    }
}
