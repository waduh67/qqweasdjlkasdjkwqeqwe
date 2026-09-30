package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReservationStockQueries
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.ReservationChange
import com.duluin.ftth.inventory.application.port.outbound.ReservationState
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class ReservationAllocationPlan(val changes: List<ReservationChange>, val candidates: List<ReservationCandidate>)

@Service
class ReservationAllocationPlanning(private val stock: ReservationStockQueries) {
    fun prepare(lines: List<ReservationDemandLine>, selections: List<ReservationSelection>, rows: List<ReservationChange>, expiry: Instant,
        access: WarehouseQueryAccess): ReservationAllocationPlan {
        if (selections.any { selection -> lines.none { it.id == selection.demandLineId } }) masterFailure(WarehouseErrorCode.NOT_FOUND)
        val selected = lines.filter { selections.isEmpty() || selections.any { selection -> it.id == selection.demandLineId } }
        val needs = selected.associateWith { line -> ReservationPlanning.wanted(line, selections.singleOrNull { it.demandLineId == line.id }, rows) }
        var serials = 0L
        needs.forEach { (line, amount) ->
            if (line.tracking == "SERIAL") {
                if (amount > 2000 || serials + amount > 2000) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                serials += amount
            }
        }
        val deductions = mutableMapOf<UUID, Long>()
        val candidates = linkedMapOf<UUID, ReservationCandidate>()
        val changes = mutableListOf<ReservationChange>()
        needs.forEach { (line, wanted) ->
            if (wanted == 0L) return@forEach
            val selection = selections.singleOrNull { it.demandLineId == line.id }
            val existing = rows.filter { it.documentLineId == line.id && it.state == ReservationState.OPEN }
            val continuous = line.continuous && line.unit == StockUnit.MM
            if (continuous && existing.size > 1) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val boundIdentity = if (continuous) existing.singleOrNull()?.dimension?.stockIdentityId else null
            if (boundIdentity != null && selection?.stockIdentityId != null && boundIdentity != selection.stockIdentityId)
                masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            val found = stock.select(line, wanted, boundIdentity ?: selection?.stockIdentityId, access, deductions)
            val allocated = ReservationPlanning.allocate(line, selection, found, rows, expiry)
            if (selection?.stockIdentityId != null && allocated.isEmpty()) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            allocated.forEach { change ->
                val candidate = found.single { it.dimension == change.dimension }
                val before = rows.singleOrNull { it.id == change.id }?.unpicked?.quantityBase ?: 0
                val amount = Math.subtractExact(change.unpicked.quantityBase, before)
                candidates.putIfAbsent(candidate.balanceId, candidate)
                deductions[candidate.balanceId] = Math.addExact(deductions[candidate.balanceId] ?: 0, amount)
            }
            changes.addAll(allocated)
            if (changes.size > 2000) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
        return ReservationAllocationPlan(changes, candidates.values.toList())
    }

    fun refresh(plan: ReservationAllocationPlan, rows: List<ReservationChange>): List<ReservationCandidate> {
        val fresh = stock.refresh(plan.candidates)
        plan.candidates.forEach { candidate ->
            val current = fresh.singleOrNull { it.balanceId == candidate.balanceId } ?: masterFailure(WarehouseErrorCode.STALE_REVISION)
            if (current.dimension != candidate.dimension || current.unit != candidate.unit || current.originRevision != candidate.originRevision ||
                current.stockRevision != candidate.stockRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
            val required = plan.changes.filter { it.dimension == candidate.dimension }.fold(0L) { sum, change ->
                Math.addExact(sum, change.unpicked.quantityBase - (rows.singleOrNull { it.id == change.id }?.unpicked?.quantityBase ?: 0))
            }
            if (current.available < required) masterFailure(WarehouseErrorCode.STALE_REVISION)
        }
        return fresh
    }
}
