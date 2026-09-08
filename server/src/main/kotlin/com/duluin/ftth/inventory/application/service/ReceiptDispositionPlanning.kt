package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReceiptPersistence
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.util.UUID

data class ReceiptDecision(val input: ReceiptInspectionInput, val outputs: List<Pair<SegmentChild, String>>)
data class ReceiptDispositionPlan(val legs: List<PostingLeg>, val splits: List<PostingSplit>,
    val decisions: List<ReceiptDecision>, val state: WarehouseReceiptState)

@Component
class ReceiptDispositionPlanning(private val store: WarehouseReceiptPersistence, private val evidence: ReceiptEvidenceService) {
    fun inspect(record: ReceiptRecord, input: ReceiptInspectInput): ReceiptDispositionPlan {
        uniqueLines(input.lines.map { it.lineId })
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        val decisions = input.lines.map { inspection ->
            receiptText(inspection.reason, 1000)
            val line = record.intake.lines.singleOrNull { it.id == inspection.lineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (inspection.baseUnit != line.sku.baseUnit) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val piece = source(record, line, inspection.stockIdentityId)
            if (piece.disposition != null) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val unit = StockUnit.valueOf(line.sku.baseUnit.name)
            val accepted = StockQuantity.parseBase(inspection.acceptedBase, unit)
            val rejected = StockQuantity.parseBase(inspection.rejectedBase, unit)
            val total = accepted + rejected
            if (total.quantityBase == 0L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val quantity = StockQuantity.parseBase(piece.quantityBase, unit)
            if (total.quantityBase > quantity.quantityBase) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            evidence.requireEvidence(record.id, inspection.evidenceId)
            val parts = listOf(accepted to "ACCEPTED", rejected to inspection.rejectedDisposition.name, (quantity - total) to "PENDING").filter { it.first.quantityBase > 0 }
            val children = parts.map { (amount, disposition) ->
                SegmentChild(if (parts.size == 1) piece.stockIdentityId else UUID.randomUUID(), amount,
                    if (unit == StockUnit.EA) SegmentKind.BULK else if (disposition == "PENDING") SegmentKind.REMNANT else SegmentKind.CUT) to disposition
            }
            if (children.size > 1) {
                val dimension = dimension(line, piece)
                splits += PostingSplit(piece.stockIdentityId, piece.revision, children.map { it.first })
                legs += PostingLeg(LegDirection.OUT, dimension, quantity, line.id, InventoryStatus.QUARANTINE)
                legs += children.map { (child, _) -> PostingLeg(LegDirection.IN, dimension.copy(stockIdentityId = child.id), child.quantity, line.id, InventoryStatus.QUARANTINE) }
            }
            ReceiptDecision(inspection, children.filter { it.second != "PENDING" })
        }
        return ReceiptDispositionPlan(legs, splits, decisions, record.state)
    }

    fun putaway(record: ReceiptRecord, input: ReceiptPutawayInput, destination: LocationSnapshot): ReceiptDispositionPlan {
        uniqueLines(input.lines.map { it.lineId })
        if (destination.kind != LocationKind.BIN || !destination.issueEligible || destination.state != WarehouseMasterState.ACTIVE)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        input.lines.forEach { placement ->
            val line = record.intake.lines.singleOrNull { it.id == placement.lineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (placement.baseUnit != line.sku.baseUnit) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val piece = source(record, line, placement.stockIdentityId)
            if (piece.disposition != "ACCEPTED" && (line.sku.inspectionRequired || piece.disposition != null)) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val amount = positiveReceiptQuantity(placement.quantityBase, placement.baseUnit)
            val total = StockQuantity.parseBase(piece.quantityBase, amount.unit)
            if (amount.quantityBase > total.quantityBase) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            val source = dimension(line, piece)
            legs += PostingLeg(LegDirection.OUT, source, total, line.id, InventoryStatus.QUARANTINE)
            val target = source.copy(locationId = destination.id, custodianId = destination.id, condition = WarehouseCondition.SERVICEABLE)
            if (amount == total) legs += PostingLeg(LegDirection.IN, target, amount, line.id, InventoryStatus.AVAILABLE)
            else {
                val moved = SegmentChild(UUID.randomUUID(), amount, if (amount.unit == StockUnit.MM) SegmentKind.CUT else SegmentKind.BULK)
                val remainder = SegmentChild(UUID.randomUUID(), total - amount, if (amount.unit == StockUnit.MM) SegmentKind.REMNANT else SegmentKind.BULK)
                splits += PostingSplit(piece.stockIdentityId, piece.revision, listOf(moved, remainder))
                legs += PostingLeg(LegDirection.IN, target.copy(stockIdentityId = moved.id), moved.quantity, line.id, InventoryStatus.AVAILABLE)
                legs += PostingLeg(LegDirection.IN, source.copy(stockIdentityId = remainder.id), remainder.quantity, line.id, InventoryStatus.QUARANTINE)
            }
        }
        val placed = input.lines.associate { it.stockIdentityId to it.quantityBase.toLong() }
        val complete = record.intake.lines.all { line -> store.pieces(line.id).all { piece ->
            piece.locationId != record.intake.inspection.id || piece.disposition in setOf("QUARANTINE", "SUPPLIER_RETURN") || placed[piece.stockIdentityId] == piece.quantityBase.toLong()
        } }
        return ReceiptDispositionPlan(legs, splits, emptyList(), if (complete) WarehouseReceiptState.PUTAWAY else record.state)
    }

    private fun source(record: ReceiptRecord, line: ReceiptIntakeLine, identity: UUID): ReceiptPiece {
        val piece = store.pieces(line.id).singleOrNull { it.stockIdentityId == identity } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (piece.locationId != record.intake.inspection.id || piece.condition != WarehouseCondition.QUARANTINE || piece.legalOwner != AssetLegalOwner.ISP ||
            piece.status != "QUARANTINE" || piece.custodianKind != "WAREHOUSE" || piece.custodianId != record.intake.inspection.id)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return piece
    }
    private fun dimension(line: ReceiptIntakeLine, piece: ReceiptPiece) = PostingDimension(line.sku.id, piece.stockIdentityId, piece.lotId,
        piece.locationId, piece.custodianId, OwnerKind.WAREHOUSE, piece.condition, piece.legalOwner)
    private fun uniqueLines(ids: List<UUID>) {
        if (ids.isEmpty() || ids.size > 500 || ids.distinct().size != ids.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
