package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.domain.model.MovementKind
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarehousePostingITTerminal : WarehousePostingRegressionSupport() {
    @Test fun `consumed stock remains terminal through a zero quantity intermediate update`() {
        val fixture = fixture()
        fixture.transaction {
            val piece = acknowledge(receipt(StockQuantity.each("1")))
            val target = piece.copy(locationId = consumed, custodianId = customer, custodianKind = OwnerKind.CUSTOMER)
            post(move(piece, target, StockQuantity.each("1"), MovementKind.CONSUME, facts = listOf(fact(piece))))
        }

        assertThatThrownBy { fixture.transaction {
            sql("UPDATE inventory_balance_projection SET quantity_base=0,status='ISSUED',revision=revision+1 WHERE status='CONSUMED'")
            sql("UPDATE inventory_balance_projection SET quantity_base=1,revision=revision+1 WHERE location_id='$consumed'")
        } }.hasStackTraceContaining("consumed material cannot become reusable custody")

        fixture.transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("1")
        }
    }
}
