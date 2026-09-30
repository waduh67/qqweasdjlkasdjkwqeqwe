package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

abstract class WarehouseReplenishmentInboundFixture : WarehouseReplenishmentFixture() {
    internal data class Inbound(val token: String, val fixture: WarehousePostingFixture, val target: UUID, val document: UUID)

    internal fun inbound(): Inbound {
        val (token, fixture) = prepare()
        val target = UUID.fromString(create("locations", token, """{"code":"TARGET","name":"Target","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString())
        val transit = UUID.fromString(create("locations", token, """{"code":"TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString())
        val document = fixture.transaction {
            val piece = receipt(StockQuantity.metres("100"))
            val travelling = piece.copy(locationId = transit, custodianId = transit, custodianKind = OwnerKind.TRANSIT)
            val dispatch = move(piece, travelling, StockQuantity.metres("100"))
            val line = dispatch.legs.first().documentLineId
            sql("UPDATE inventory_document SET work_order_id=NULL,customer_id=NULL,revision=revision+1 WHERE id='${dispatch.documentId}'")
            sql("UPDATE inventory_document_line SET destination_location_id='$target',document_revision=1,revision=revision+1 WHERE id='$line'")
            post(dispatch.copy(expectedRevision = 1, legs = dispatch.legs.map {
                if (it.direction == LegDirection.IN) it.copy(status = InventoryStatus.IN_TRANSIT) else it
            }))
            val received = piece.copy(stockIdentityId = UUID.randomUUID(), locationId = target, custodianId = target)
            val remaining = travelling.copy(stockIdentityId = UUID.randomUUID())
            post(WarehousePost(dispatch.documentId, 2, "PART_RECEIVED", operation("PART_RECEIVE"), MovementKind.TRANSFER,
                "Confirmed partial inbound", listOf(
                    PostingLeg(LegDirection.OUT, travelling, StockQuantity.metres("100"), line, InventoryStatus.IN_TRANSIT),
                    PostingLeg(LegDirection.IN, received, StockQuantity.metres("60"), line, InventoryStatus.AVAILABLE),
                    PostingLeg(LegDirection.IN, remaining, StockQuantity.metres("40"), line, InventoryStatus.IN_TRANSIT)),
                splits = listOf(PostingSplit(piece.stockIdentityId, 0, listOf(
                    SegmentChild(received.stockIdentityId, StockQuantity.metres("60"), SegmentKind.CUT),
                    SegmentChild(remaining.stockIdentityId, StockQuantity.metres("40"), SegmentKind.REMNANT))))))
            dispatch.documentId
        }
        reserve(demand(token, fixture, 20000, false))
        return Inbound(token, fixture, target, document)
    }

    internal fun inboundRule(scenario: Inbound) = ruleBody(scenario.fixture)
        .replace(scenario.fixture.warehouse.toString(), scenario.target.toString())
        .replace("\"50000\"", "\"100000\"").replace("\"maximumBase\":\"100000\"", "\"maximumBase\":\"150000\"")
        .replace("\"targetBase\":\"100000\"", "\"targetBase\":\"150000\"")
}
