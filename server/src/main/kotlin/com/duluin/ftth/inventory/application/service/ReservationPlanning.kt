package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.time.Instant
import java.util.UUID

data class ReservationDemand(val id: UUID, val revision: Long, val workOrder: UUID, val planRevision: Long,
    val submittedAt: Instant, val state: String, val actor: UUID, val cutoverEpoch: Long)
data class ReservationDemandLine(val id: UUID, val planLineId: UUID, val sku: UUID, val unit: StockUnit,
    val requested: Long, val continuous: Boolean, val tracking: String)
data class ReservationCandidate(val dimension: PostingDimension, val unit: StockUnit, val available: Long,
    val receivedAt: Instant, val originDocument: UUID, val originLine: UUID, val originRevision: Long, val stockRevision: Long, val balanceId: UUID)
data class ReservationRow(val change: ReservationChange, val state: ReservationState)
data class ReservationLineSupply(val demandLineId: UUID, val planLineId: UUID, val requestedBase: String,
    val reservedUnpickedBase: String, val reservedPickedBase: String, val backorderBase: String, val baseUnit: StockUnit)

internal object ReservationPlanning {
    fun quantity(raw: String): Long = raw.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull()
        ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)

    fun wanted(line: ReservationDemandLine, selection: ReservationSelection?, existing: List<ReservationChange>): Long {
        val current = existing.filter { it.documentLineId == line.id && it.state == ReservationState.OPEN }
        val reserved = current.fold(0L) { total, row -> Math.addExact(total, (row.unpicked + row.picked).quantityBase) }
        val remaining = Math.subtractExact(line.requested, reserved)
        val wanted = selection?.partialQuantityBase?.let(::quantity) ?: remaining
        if (wanted > remaining || remaining < 0) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        return wanted
    }

    fun allocate(line: ReservationDemandLine, selection: ReservationSelection?, candidates: List<ReservationCandidate>,
        existing: List<ReservationChange>, expiresAt: Instant): List<ReservationChange> {
        val wanted = wanted(line, selection, existing)
        val current = existing.filter { it.documentLineId == line.id && it.state == ReservationState.OPEN }
        if (wanted == 0L) return emptyList()
        var ordered = candidates.filter { it.dimension.skuId == line.sku && it.unit == line.unit && it.available > 0 }
            .sortedWith(compareBy({ it.receivedAt }, { it.dimension.stockIdentityId.toString() }, { it.dimension.orderKey() }))
        selection?.stockIdentityId?.let { id -> ordered = ordered.filter { it.dimension.stockIdentityId == id } }
        if (line.continuous && line.unit == StockUnit.MM) {
            val identity = current.singleOrNull()?.dimension?.stockIdentityId
            if (current.size > 1) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            ordered = ordered.filter { it.available >= wanted && (identity == null || it.dimension.stockIdentityId == identity) }.take(1)
        }
        var left = wanted
        return buildList {
            for (candidate in ordered) {
                if (left == 0L) break
                val amount = minOf(left, candidate.available, if (line.tracking == "SERIAL") 1L else Long.MAX_VALUE)
                val before = current.singleOrNull { it.dimension == candidate.dimension }
                add(ReservationChange(before?.id ?: UUID.randomUUID(), line.id, candidate.dimension, before?.expectedRevision,
                    StockQuantity.of(Math.addExact(before?.unpicked?.quantityBase ?: 0L, amount), line.unit),
                    before?.picked ?: StockQuantity.of(0, line.unit), before?.expiresAt ?: expiresAt))
                left -= amount
            }
        }
    }

    fun supplies(lines: List<ReservationDemandLine>, rows: List<ReservationChange>) = lines.map { line ->
        val active = rows.filter { it.documentLineId == line.id && it.state == ReservationState.OPEN }
        val unpicked = active.fold(0L) { sum, row -> Math.addExact(sum, row.unpicked.quantityBase) }
        val picked = active.fold(0L) { sum, row -> Math.addExact(sum, row.picked.quantityBase) }
        val backorder = Math.subtractExact(line.requested, Math.addExact(unpicked, picked))
        if (backorder < 0) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        ReservationLineSupply(line.id, line.planLineId, line.requested.toString(), unpicked.toString(), picked.toString(), backorder.toString(), line.unit)
    }
    fun state(lines: List<ReservationLineSupply>): String = when {
        lines.all { it.backorderBase == "0" } -> "RESERVED"
        lines.any { it.reservedPickedBase != "0" || it.reservedUnpickedBase != "0" } -> "PART_RESERVED"
        else -> "SUBMITTED"
    }
}
