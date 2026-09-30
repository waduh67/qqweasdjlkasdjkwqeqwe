package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Component
class WarehouseIssuePicking(private val store: WarehouseIssueStore, private val stock: ReservationStockQueries) {
    fun prepare(request: WarehousePickRequest, plan: MaterialPlanSnapshot, demandLines: List<ReservationDemandLine>,
        rows: List<ReservationChange>, now: Instant): PreparedIssuePick {
        if (request.lines.isEmpty() || request.lines.size > 100 || request.lines.distinctBy { it.reservationId }.size != request.lines.size ||
            request.lines.distinctBy { it.stockIdentityId }.size != request.lines.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val changes = mutableListOf<ReservationChange>()
        val splits = mutableListOf<PostingSplit>()
        val legs = mutableListOf<PostingLeg>()
        val candidates = mutableListOf<ReservationCandidate>()
        val lines = request.lines.map { input ->
            val row = rows.singleOrNull { it.id == input.reservationId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (row.state != ReservationState.OPEN || row.expectedRevision != input.expectedRevision || row.picked.quantityBase != 0L)
                masterFailure(WarehouseErrorCode.STALE_REVISION)
            if (row.dimension.stockIdentityId != input.stockIdentityId || row.unpicked.unit.name != input.baseUnit.name)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val quantity = StockQuantity.of(ReservationPlanning.quantity(input.quantityBase), row.unpicked.unit)
            if (quantity.quantityBase > row.unpicked.quantityBase || row.expiresAt <= now) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            val demand = demandLines.singleOrNull { it.id == row.documentLineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            val planLine = plan.lines.single { it.id == demand.planLineId }
            val candidate = stock.position(row.dimension)
            val piece = store.stock(input.stockIdentityId, row.dimension.locationId)
            if (piece.revision != input.stockRevision || candidate.available < 0 || row.dimension.custodianKind != OwnerKind.WAREHOUSE)
                masterFailure(WarehouseErrorCode.STALE_REVISION)
            if ((demand.tracking == "SERIAL" && (quantity.quantityBase != 1L || input.baseUnit != WarehouseBaseUnit.EA)) ||
                (input.scan != null && (piece.serial == null || input.scan.trim().uppercase(Locale.ROOT) != piece.serial)))
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val lineId = UUID.randomUUID()
            val zero = StockQuantity.of(0, quantity.unit)
            val split = quantity.unit == StockUnit.MM && quantity.quantityBase < piece.quantity
            val pickedDimension = if (split) row.dimension.copy(stockIdentityId = UUID.randomUUID()) else row.dimension
            val picked = if (split) row.copy(dimension = pickedDimension, unpicked = zero, picked = quantity, partitionFrom = row.id)
                else row.copy(unpicked = row.unpicked - quantity, picked = quantity)
            changes.add(picked)
            if (split) {
                val remainder = row.dimension.copy(stockIdentityId = UUID.randomUUID())
                val remaining = StockQuantity.of(piece.quantity - quantity.quantityBase, quantity.unit)
                splits.add(PostingSplit(input.stockIdentityId, input.stockRevision, listOf(
                    SegmentChild(pickedDimension.stockIdentityId, quantity, SegmentKind.CUT), SegmentChild(remainder.stockIdentityId, remaining, SegmentKind.REMNANT))))
                legs.add(PostingLeg(LegDirection.OUT, row.dimension, StockQuantity.of(piece.quantity, quantity.unit), lineId, InventoryStatus.AVAILABLE))
                legs.add(PostingLeg(LegDirection.IN, pickedDimension, quantity, lineId, InventoryStatus.AVAILABLE))
                legs.add(PostingLeg(LegDirection.IN, remainder, remaining, lineId, InventoryStatus.AVAILABLE))
                rows.filter { it.id != row.id && it.state == ReservationState.OPEN && it.dimension == row.dimension }.forEach { other ->
                    if (other.picked.quantityBase != 0L) masterFailure(WarehouseErrorCode.STALE_REVISION)
                    changes.add(other.copy(dimension = remainder, partitionFrom = other.id))
                }
                if (row.unpicked.quantityBase > quantity.quantityBase) {
                    changes.add(row.copy(id = UUID.randomUUID(), expectedRevision = null, dimension = remainder,
                        unpicked = row.unpicked - quantity, picked = zero, partitionFrom = row.id))
                    candidates.add(candidate.copy(dimension = remainder, stockRevision = 0))
                }
            }
            IssuePickedLine(lineId, row.documentLineId, demand.planLineId, row.id, Math.addExact(input.expectedRevision, 1),
                pickedDimension, input.stockIdentityId, input.quantityBase, input.baseUnit, planLine.sku,
                piece.serial, piece.lot, piece.location, planLine.substitution, planLine.originalSku)
        }
        return PreparedIssuePick(lines, legs, splits, changes, candidates)
    }
}
