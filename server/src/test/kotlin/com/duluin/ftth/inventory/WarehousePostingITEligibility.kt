package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehousePostingITEligibility : WarehousePostingRegressionSupport() {
    @ParameterizedTest @ValueSource(strings=["IN_TRANSIT","ISSUED","QUARANTINE","RETURNED","NONELIGIBLE","WRONG_KIND","CUSTOMER_OWNER","QUARANTINE_CONDITION"])
    fun `AV5-03 reservation rejects unavailable or unapproved physical positions`(mode: String) {
        val fixture=fixture()
        val piece=fixture.transaction {
            val received=receipt(StockQuantity.each("1"))
            sql("UPDATE inventory_location SET issue_eligible=${mode!="NONELIGIBLE"},revision=revision+1 WHERE id='$technician'")
            val target=received.copy(locationId=technician,custodianId=actor,
                custodianKind=if(mode=="WRONG_KIND") OwnerKind.WAREHOUSE else OwnerKind.TECHNICIAN,
                legalOwner=if(mode=="CUSTOMER_OWNER") AssetLegalOwner.CUSTOMER else AssetLegalOwner.ISP,
                condition=if(mode=="QUARANTINE_CONDITION") WarehouseCondition.QUARANTINE else WarehouseCondition.SERVICEABLE)
            val status=InventoryStatus.entries.find { it.name==mode } ?: InventoryStatus.AVAILABLE
            val command=move(received,target,StockQuantity.each("1"))
            post(command.copy(legs=command.legs.map { if(it.direction==LegDirection.IN) it.copy(status=status) else it }))
            target
        }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction { post(reservation(piece,StockQuantity.each("1"))) } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `AV5-03 available approved stock permits reservation but unavailable increase fails`() {
        val fixture=fixture()
        val pair=fixture.transaction {
            val piece=receipt(StockQuantity.each("100"),true)
            sql("UPDATE inventory_location SET issue_eligible=true,revision=revision+1 WHERE id='$warehouse'")
            val reserve=reservation(piece,StockQuantity.each("40"))
            post(reserve)
            post(reclassify(piece,StockQuantity.each("100"),InventoryStatus.AVAILABLE,InventoryStatus.IN_TRANSIT))
            piece to reserve
        }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            val reserve=pair.second
            post(reserve.copy(expectedRevision=1,operation=operation("INCREASE"),reservations=listOf(reserve.reservations.single().copy(expectedRevision=0,unpicked=StockQuantity.each("60")))))
        } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `AV5-03 existing accountability can decrease and release after eligibility changes`() {
        val fixture=fixture()
        fixture.transaction {
            val piece=receipt(StockQuantity.each("100"),true)
            sql("UPDATE inventory_location SET issue_eligible=true,revision=revision+1 WHERE id='$warehouse'")
            val reserve=reservation(piece,StockQuantity.each("40"))
            post(reserve)
            post(reclassify(piece,StockQuantity.each("100"),InventoryStatus.AVAILABLE,InventoryStatus.IN_TRANSIT))
            sql("UPDATE inventory_location SET issue_eligible=false,revision=revision+1 WHERE id='$warehouse'")
            val decreased=reserve.reservations.single().copy(expectedRevision=0,unpicked=StockQuantity.each("20"))
            post(reserve.copy(expectedRevision=1,operation=operation("DECREASE"),kind=MovementKind.RELEASE,reservations=listOf(decreased)))
            post(reserve.copy(expectedRevision=2,operation=operation("RELEASE"),kind=MovementKind.RELEASE,
                reservations=listOf(decreased.copy(expectedRevision=1,unpicked=StockQuantity.each("0"),state=ReservationState.RELEASED))))
            assertThat(scalar("SELECT state||':'||reserved_unpicked_base FROM inventory_reservation")).isEqualTo("RELEASED:0")
            assertThat(total(warehouse,StockUnit.EA)).isEqualTo(100)
        }
    }
}
