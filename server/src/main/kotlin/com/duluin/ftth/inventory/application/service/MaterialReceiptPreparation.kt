package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.MaterialReceiptSelection
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseTracking
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialReceiptStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseIssueStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LegDirection
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.springframework.stereotype.Component
import java.util.UUID

data class MaterialReceiptSource(val issue: IssuePickedLine, val transit: PostingDimension, val previouslyAccepted: Long)
data class MaterialReceiptDestination(val receiver: UUID, val location: UUID)

@Component
class MaterialReceiptPreparation(private val store: MaterialReceiptStore, private val issues: WarehouseIssueStore) {
    fun prepare(input: MaterialReceiptSelection, source: MaterialReceiptSource, destination: MaterialReceiptDestination): PreparedMaterialReceipt {
        if (input.stockIdentityId != source.issue.dimension.stockIdentityId || input.baseUnit != source.issue.baseUnit)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        when (source.issue.sku.tracking) {
            WarehouseTracking.SERIAL -> if (input.serial != source.issue.serial) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            WarehouseTracking.LOT, WarehouseTracking.BULK -> if (input.serial != null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
        val accepted = quantity(input.acceptedBase)
        val missing = quantity(input.missingBase)
        val rejected = quantity(input.rejectedBase)
        val remaining = Math.subtractExact(source.issue.quantityBase.toLong(), source.previouslyAccepted)
        if (accepted > remaining || missing > remaining - accepted || rejected > remaining - accepted - missing)
            masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        if (accepted + missing + rejected == 0L || (input.reason?.length ?: 0) > 1000 ||
            ((missing > 0 || rejected > 0) && input.reason.isNullOrBlank())) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val identity = store.remainingIdentity(input.issueLineId, input.stockIdentityId)
        val piece = issues.stock(identity, source.transit.locationId)
        val transit = source.transit.copy(stockIdentityId = identity)
        val rest = remaining - accepted
        val splitRequired = input.baseUnit == WarehouseBaseUnit.MM && accepted > 0 && rest > 0
        val receivedIdentity = if (splitRequired) UUID.randomUUID() else identity
        val remainderIdentity = if (splitRequired) UUID.randomUUID() else identity
        val received = if (accepted == 0L) null else transit.copy(stockIdentityId = receivedIdentity,
            locationId = destination.location, custodianKind = OwnerKind.TECHNICIAN, custodianId = destination.receiver)
        val remainder = if (rest == 0L) null else transit.copy(stockIdentityId = remainderIdentity)
        val unit = StockUnit.valueOf(input.baseUnit.name)
        val legs = buildList {
            if (received != null) {
                add(PostingLeg(LegDirection.OUT, transit, StockQuantity.of(if (splitRequired) remaining else accepted, unit), input.issueLineId, InventoryStatus.IN_TRANSIT))
                add(PostingLeg(LegDirection.IN, received, StockQuantity.of(accepted, unit), input.issueLineId, InventoryStatus.ISSUED))
                if (splitRequired) add(PostingLeg(LegDirection.IN, requireNotNull(remainder), StockQuantity.of(rest, unit), input.issueLineId, InventoryStatus.IN_TRANSIT))
            }
        }
        val split = if (splitRequired) PostingSplit(identity, piece.revision, listOf(
            SegmentChild(receivedIdentity, StockQuantity.of(accepted, unit), SegmentKind.CUT),
            SegmentChild(remainderIdentity, StockQuantity.of(rest, unit), SegmentKind.REMNANT))) else null
        return PreparedMaterialReceipt(MaterialReceiptLineSnapshot(input, transit, piece.revision, received, remainder,
            source.previouslyAccepted.toString(), rest.toString()), legs, split)
    }

    private fun quantity(value: String): Long {
        if (!value.matches(Regex("0|[1-9][0-9]{0,18}"))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return value.toLongOrNull() ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
