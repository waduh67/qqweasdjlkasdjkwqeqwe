package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WarehouseReservationPlanningTest {
    private val sku = UUID.randomUUID()
    private val now = Instant.parse("2026-09-01T00:00:00Z")
    private val line = ReservationDemandLine(UUID.randomUUID(), UUID.randomUUID(), sku, StockUnit.MM, 15000, true, "LOT")
    private fun candidate(quantity: Long, time: Instant = now) = ReservationCandidate(
        PostingDimension(sku, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), OwnerKind.WAREHOUSE,
            WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP), StockUnit.MM, quantity, time, UUID.randomUUID(), UUID.randomUUID(), 2, 0)

    @Test fun `two remnants cannot supply continuous cut but explicit partial may use one`() {
        val stock = listOf(candidate(10000), candidate(10000))
        assertThat(ReservationPlanning.allocate(line, null, stock, emptyList(), now.plusSeconds(86400))).isEmpty()
        val partial = ReservationPlanning.allocate(line, ReservationSelection(line.id, "9000"), stock, emptyList(), now.plusSeconds(86400))
        assertThat(partial).hasSize(1)
        assertThat(partial.single().unpicked.quantityBase).isEqualTo(9000)
        assertThat(ReservationPlanning.supplies(listOf(line), partial).single().backorderBase).isEqualTo("6000")
    }
    @Test fun `FIFO chooses oldest adequate piece and never joins later partial onto another piece`() {
        val old = candidate(20000)
        val later = candidate(20000, now.plusSeconds(10))
        val first = ReservationPlanning.allocate(line, ReservationSelection(line.id, "9000"), listOf(later, old), emptyList(), now.plusSeconds(86400))
        assertThat(first.single().dimension).isEqualTo(old.dimension)
        val noRoom = ReservationPlanning.allocate(line, null, listOf(later, old.copy(available = 1000)), first, now.plusSeconds(86400))
        assertThat(noRoom).isEmpty()
    }
    @Test fun `picked and unpicked form one commitment`() {
        val allocation = ReservationPlanning.allocate(line.copy(continuous = false), null, listOf(candidate(10000)), emptyList(), now.plusSeconds(86400)).single()
        val picked = allocation.copy(unpicked = StockQuantity.of(4000, StockUnit.MM), picked = StockQuantity.of(6000, StockUnit.MM))
        val summary = ReservationPlanning.supplies(listOf(line), listOf(picked))
        assertThat(summary.single().backorderBase).isEqualTo("5000")
        assertThat(ReservationPlanning.state(summary)).isEqualTo("PART_RESERVED")
    }
}
