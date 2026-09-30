package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseTransferStock
import com.duluin.ftth.inventory.application.port.inbound.LocationSnapshot
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.util.UUID

data class TransferPosting(val lines: List<TransferLine>, val legs: List<PostingLeg>, val splits: List<PostingSplit>)

@Component
class WarehouseTransferPlanning(private val stock: WarehouseTransferStock) {
    fun draft(request: WarehouseTransferDraft, actor: UUID): List<TransferLine> {
        if (request.reason.isBlank() || request.reason.length > 1000 || request.lines.isEmpty() || request.lines.size > 100 ||
            request.lines.distinctBy { it.stockIdentityId }.size != request.lines.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return request.lines.map { selection ->
            val quantity = transferQuantity(selection.quantityBase)
            val source = stock.get(selection.stockIdentityId, request.sourceLocationId, balanceId = selection.sourceBalanceId)
            stock.assertUnallocated(selection.stockIdentityId)
            if (source.unit != selection.baseUnit) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            if (source.status !in setOf(InventoryStatus.AVAILABLE, InventoryStatus.QUARANTINE, InventoryStatus.RETURNED))
                masterFailure(WarehouseErrorCode.USE_WORKORDER_ASSET_WORKFLOW)
            if (source.dimension.custodianKind in setOf(OwnerKind.TECHNICIAN, OwnerKind.VEHICLE) && source.dimension.custodianId != actor)
                masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
            if (quantity > source.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (source.tracking == WarehouseTracking.SERIAL && quantity != 1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            TransferLine(UUID.randomUUID(), source, quantity)
        }
    }

    fun dispatch(record: TransferRecord): TransferPosting {
        val movements = record.lines.map { line ->
            val current = stock.get(line.source.dimension.stockIdentityId, record.binding.sourceLocationId, dimension = line.source.dimension)
            stock.assertUnallocated(current.dimension.stockIdentityId)
            if (current != line.source) masterFailure(WarehouseErrorCode.STALE_REVISION)
            move(line, current, record.transitDimension(current.dimension), InventoryStatus.IN_TRANSIT, line.quantity, true)
        }
        return combine(movements)
    }

    fun receive(record: TransferRecord, input: WarehouseTransferReceipt, destination: LocationSnapshot): TransferPosting {
        if (input.evidenceReference.isBlank() || input.evidenceReference.length > 500 || input.lines.isEmpty() || input.lines.size > 100 ||
            input.lines.distinctBy { it.lineId }.size != input.lines.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val movements = input.lines.associate { selection ->
            val line = record.lines.singleOrNull { it.id == selection.lineId } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val quantity = transferQuantity(selection.quantityBase)
            if (quantity > line.quantity - line.received) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (selection.baseUnit != line.source.unit) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val identity = line.remainingIdentity ?: masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            val expectedDimension = record.transitDimension(line.source.dimension).copy(stockIdentityId = identity)
            val current = stock.get(identity, record.binding.transitLocationId, dimension = expectedDimension)
            if (current.dimension != expectedDimension ||
                current.status != InventoryStatus.IN_TRANSIT || current.quantity != line.quantity - line.received)
                masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
            val custody = when (destination.kind) {
                LocationKind.TECHNICIAN -> OwnerKind.TECHNICIAN
                LocationKind.VEHICLE -> OwnerKind.VEHICLE
                else -> OwnerKind.WAREHOUSE
            }
            val target = current.dimension.copy(locationId = destination.id, custodianKind = custody,
                custodianId = if (custody == OwnerKind.WAREHOUSE) destination.id else record.binding.receiverId)
            val available = line.source.status == InventoryStatus.AVAILABLE && destination.issueEligible &&
                target.condition == WarehouseCondition.SERVICEABLE && target.legalOwner == AssetLegalOwner.ISP
            val status = if (available) InventoryStatus.AVAILABLE else InventoryStatus.QUARANTINE
            selection.lineId to move(line, current, target, status, quantity, false)
        }
        val combined = combine(movements.values.toList())
        return combined.copy(lines = record.lines.map { line -> movements[line.id]?.lines?.single() ?: line })
    }

    private fun move(line: TransferLine, source: TransferStock, target: PostingDimension, status: InventoryStatus,
        quantity: Long, dispatch: Boolean): TransferPosting {
        val rest = source.quantity - quantity
        val split = source.unit == WarehouseBaseUnit.MM && rest > 0
        val acceptedIdentity = if (split) UUID.randomUUID() else source.dimension.stockIdentityId
        val remainderIdentity = if (split) UUID.randomUUID() else source.dimension.stockIdentityId
        val unit = StockUnit.valueOf(source.unit.name)
        val legs = buildList {
            add(PostingLeg(LegDirection.OUT, source.dimension, StockQuantity.of(if (split) source.quantity else quantity, unit), line.id, source.status))
            add(PostingLeg(LegDirection.IN, target.copy(stockIdentityId = acceptedIdentity), StockQuantity.of(quantity, unit), line.id, status))
            if (split) add(PostingLeg(LegDirection.IN, source.dimension.copy(stockIdentityId = remainderIdentity), StockQuantity.of(rest, unit), line.id, source.status))
        }
        val splits = if (split) listOf(PostingSplit(source.dimension.stockIdentityId, source.revision,
            listOf(SegmentChild(acceptedIdentity, StockQuantity.of(quantity, unit), SegmentKind.CUT),
                SegmentChild(remainderIdentity, StockQuantity.of(rest, unit), SegmentKind.REMNANT)))) else emptyList()
        val updated = if (dispatch) line.copy(remainingIdentity = acceptedIdentity) else
            line.copy(received = Math.addExact(line.received, quantity), remainingIdentity = if (rest == 0L) null else remainderIdentity)
        return TransferPosting(listOf(updated), legs, splits)
    }

    private fun combine(movements: List<TransferPosting>): TransferPosting = TransferPosting(
        movements.flatMap { it.lines }, movements.flatMap { it.legs }, movements.flatMap { it.splits })
}
