package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehousePostingITPickedTransitions : WarehousePostingRegressionSupport() {
    @ParameterizedTest @ValueSource(strings=["40","20"])
    fun `ineligible picked increases reject even at unchanged or reduced total`(picked: String) {
        val fixture=fixture()
        val reserve=fixture.transaction {
            val piece=receipt(StockQuantity.each("100"),true)
            val command=reservation(piece,StockQuantity.each("40"))
            post(command)
            post(reclassify(piece,StockQuantity.each("100"),InventoryStatus.AVAILABLE,InventoryStatus.IN_TRANSIT))
            sql("UPDATE inventory_location SET issue_eligible=false,revision=revision+1 WHERE id='$warehouse'")
            command
        }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            post(reserve.copy(expectedRevision=1,operation=operation("PICK"),reservations=listOf(reserve.reservations.single().copy(
                expectedRevision=0,unpicked=StockQuantity.each("0"),picked=StockQuantity.each(picked)))))
        } }
        val observed=fixture.transaction { pickedState() }
        println("PICKED_PROBE committed=${result.isSuccess} observed=$observed")
        assertThat(result.isFailure).isTrue()
        assertThat(observed).isEqualTo("IN_TRANSIT|false|40|0")
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `eligible pick and explicit unpick retain one encumbrance`() {
        val fixture=fixture()
        fixture.transaction {
            val piece=receipt(StockQuantity.each("100"),true)
            val reserve=reservation(piece,StockQuantity.each("40"))
            post(reserve)
            val picked=reserve.reservations.single().copy(expectedRevision=0,unpicked=StockQuantity.each("0"),picked=StockQuantity.each("40"))
            post(reserve.copy(expectedRevision=1,operation=operation("PICK"),reservations=listOf(picked)))
            assertThat(pickedState()).isEqualTo("AVAILABLE|true|0|40")
            assertThat(available()).isEqualTo("60")
            post(reserve.copy(expectedRevision=2,operation=operation("UNPICK"),reservations=listOf(
                picked.copy(expectedRevision=1,unpicked=StockQuantity.each("40"),picked=StockQuantity.each("0")))))
            assertThat(pickedState()).isEqualTo("AVAILABLE|true|40|0")
            assertThat(available()).isEqualTo("60")
            assertThat(total(warehouse,StockUnit.EA)).isEqualTo(100)
        }
    }

    @Test fun `ineligible decrease unpick and release cannot mint availability`() {
        val fixture=fixture()
        fixture.transaction {
            val piece=receipt(StockQuantity.each("100"),true)
            val reserve=reservation(piece,StockQuantity.each("40"))
            post(reserve)
            val picked=reserve.reservations.single().copy(expectedRevision=0,unpicked=StockQuantity.each("0"),picked=StockQuantity.each("40"))
            post(reserve.copy(expectedRevision=1,operation=operation("PICK"),reservations=listOf(picked)))
            post(reclassify(piece,StockQuantity.each("100"),InventoryStatus.AVAILABLE,InventoryStatus.IN_TRANSIT))
            sql("UPDATE inventory_location SET issue_eligible=false,revision=revision+1 WHERE id='$warehouse'")
            val reduced=picked.copy(expectedRevision=1,picked=StockQuantity.each("20"))
            post(reserve.copy(expectedRevision=2,operation=operation("DECREASE"),kind=MovementKind.RELEASE,reservations=listOf(reduced)))
            val unpicked=reduced.copy(expectedRevision=2,unpicked=StockQuantity.each("20"),picked=StockQuantity.each("0"))
            post(reserve.copy(expectedRevision=3,operation=operation("UNPICK"),reservations=listOf(unpicked)))
            assertThat(pickedState()).isEqualTo("IN_TRANSIT|false|20|0")
            assertThat(available()).isEqualTo("0")
            post(reserve.copy(expectedRevision=4,operation=operation("RELEASE"),kind=MovementKind.RELEASE,reservations=listOf(
                unpicked.copy(expectedRevision=3,unpicked=StockQuantity.each("0"),state=ReservationState.RELEASED))))
            assertThat(pickedState()).isEqualTo("IN_TRANSIT|false|0|0")
            assertThat(available()).isEqualTo("0")
            assertThat(total(warehouse,StockUnit.EA)).isEqualTo(100)
        }
    }

    private fun WarehousePostingFixture.pickedState() = scalar("""SELECT balance.status||'|'||location.issue_eligible||'|'||reservation.reserved_unpicked_base||'|'||reservation.reserved_picked_base
        FROM inventory_reservation reservation JOIN inventory_balance_projection balance ON balance.stock_identity_id=reservation.stock_identity_id AND balance.location_id=reservation.location_id
        JOIN inventory_location location ON location.id=balance.location_id""")

    private fun WarehousePostingFixture.available() = scalar("""SELECT CASE WHEN balance.status='AVAILABLE' AND location.issue_eligible
        THEN balance.quantity_base-reservation.reserved_unpicked_base-reservation.reserved_picked_base ELSE 0 END
        FROM inventory_reservation reservation JOIN inventory_balance_projection balance ON balance.stock_identity_id=reservation.stock_identity_id AND balance.location_id=reservation.location_id
        JOIN inventory_location location ON location.id=balance.location_id""")
}
