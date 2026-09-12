package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseIssueStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.util.UUID

data class MaterialUsageSource(val receipt: MaterialReceiptSnapshot, val input: MaterialUsageSelection, val planLine: MaterialPlanSnapshotLine)
data class MaterialUsageDestination(val location: UUID, val customer: UUID?, val usage: UUID)

@Component
class MaterialUsagePreparation(private val issues: WarehouseIssueStore) {
    fun prepare(source: MaterialUsageSource, destination: MaterialUsageDestination): PreparedMaterialUsage {
        val input = source.input
        val accepted = source.receipt.lines.singleOrNull { it.selection.issueLineId == input.issueLineId }
            ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val custody = accepted.accepted ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val issued = source.receipt.issue.lines.single { it.id == input.issueLineId }
        if (custody.stockIdentityId != input.stockIdentityId || input.baseUnit != accepted.selection.baseUnit)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        when (issued.sku.tracking) {
            WarehouseTracking.SERIAL -> masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Serialized deployment requires the deployment workflow")
            WarehouseTracking.LOT, WarehouseTracking.BULK -> Unit
        }
        if (!input.quantityBase.matches(Regex("[1-9][0-9]{0,18}"))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val used = input.quantityBase.toLongOrNull() ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val acknowledged = accepted.selection.acceptedBase.toLong()
        if (used > acknowledged) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        val piece = issues.stock(custody.stockIdentityId, custody.locationId)
        val rest = Math.subtractExact(acknowledged, used)
        val splitRequired = input.baseUnit == WarehouseBaseUnit.MM && rest > 0
        val consumedId = if (splitRequired) UUID.randomUUID() else custody.stockIdentityId
        val remainderId = if (splitRequired) UUID.randomUUID() else custody.stockIdentityId
        val consumed = custody.copy(stockIdentityId = consumedId, locationId = destination.location,
            custodianKind = if (destination.customer == null) OwnerKind.TECHNICIAN else OwnerKind.CUSTOMER,
            custodianId = destination.customer ?: custody.custodianId)
        val remainder = if (rest == 0L) null else custody.copy(stockIdentityId = remainderId)
        val unit = StockUnit.valueOf(input.baseUnit.name)
        val lineId = UUID.randomUUID()
        val factId = UUID.randomUUID()
        val legs = buildList {
            add(PostingLeg(LegDirection.OUT, custody, StockQuantity.of(if (splitRequired) acknowledged else used, unit), lineId, InventoryStatus.ISSUED))
            add(PostingLeg(LegDirection.IN, consumed, StockQuantity.of(used, unit), lineId, InventoryStatus.CONSUMED, PostingEndpoint.CONSUMED))
            if (splitRequired) add(PostingLeg(LegDirection.IN, requireNotNull(remainder), StockQuantity.of(rest, unit), lineId, InventoryStatus.ISSUED))
        }
        val split = if (splitRequired) PostingSplit(custody.stockIdentityId, piece.revision, listOf(
            SegmentChild(consumedId, StockQuantity.of(used, unit), SegmentKind.CUT),
            SegmentChild(remainderId, StockQuantity.of(rest, unit), SegmentKind.REMNANT))) else null
        val line = MaterialUsageLineSnapshot(lineId, input, source.receipt.revision, source.receipt.issueId, issued.planLineId,
            source.planLine.quantityBase, acknowledged.toString(), rest.toString(), custody, piece.revision, consumed, remainder, factId)
        val fact = PostingMaterialFact(factId, consumedId, destination.customer, source.receipt.issue.workOrderId,
            issued.sku.code, StockQuantity.of(used, unit), 1, installed = true, returned = false, usageId = destination.usage)
        return PreparedMaterialUsage(line, legs, split, fact)
    }
}
