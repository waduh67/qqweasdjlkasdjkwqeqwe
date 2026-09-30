package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePostingITReservations {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }

    @Test fun `reserve pick and dispatch consume exactly one encumbrance`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction {
            val piece=receipt(StockQuantity.each("1"))
            val reserve=reservation(piece,StockQuantity.each("1"))
            post(reserve)
            val picked=reserve.reservations.single().copy(expectedRevision=0,unpicked=StockQuantity.each("0"),picked=StockQuantity.each("1"))
            post(reserve.copy(expectedRevision=1,nextState="RESERVED",operation=operation("PICK"),reservations=listOf(picked)))
            assertThat(total(warehouse,StockUnit.EA)).isEqualTo(1)
            assertThat(scalar("SELECT count(*) FROM inventory_movement_leg")).isEqualTo("2")
            val dispatch=move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1"),MovementKind.ISSUE)
            post(dispatch.copy(reservations=listOf(picked.copy(expectedRevision=1,picked=StockQuantity.each("0"),state=ReservationState.DISPATCHED))))
            assertThat(total(warehouse,StockUnit.EA)).isZero(); assertThat(total(technician,StockUnit.EA)).isEqualTo(1)
            assertThat(scalar("SELECT reserved_unpicked_base+reserved_picked_base FROM inventory_reservation")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement_leg")).isEqualTo("4")
        }
    }

    @Test fun `reserved stock cannot leave without releasing its encumbrance`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        fixture.transaction { post(reservation(piece,StockQuantity.each("1"))) }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction { post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1"))) } }
            .isInstanceOf(WarehouseContractException::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(warehouse,StockUnit.EA)).isEqualTo(1) }
    }

    @Test fun `splitting cable transfers reservations onto exact child without duplicating allocation`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction {
            val piece=receipt(StockQuantity.metres("100"))
            val reserve=reservation(piece,StockQuantity.metres("20"))
            post(reserve)
            val old=reserve.reservations.single()
            val cut=piece.copy(stockIdentityId=UUID.randomUUID())
            val rest=piece.copy(stockIdentityId=UUID.randomUUID())
            val command=move(piece,cut,StockQuantity.metres("20"),splits=listOf(PostingSplit(piece.stockIdentityId,0,listOf(
                SegmentChild(cut.stockIdentityId,StockQuantity.metres("20"),SegmentKind.CUT),SegmentChild(rest.stockIdentityId,StockQuantity.metres("80"),SegmentKind.REMNANT)))),extra=listOf(rest to StockQuantity.metres("80")))
            post(command.copy(reservations=listOf(old.copy(expectedRevision=0,unpicked=StockQuantity.metres("0"),state=ReservationState.RELEASED),
                old.copy(id=UUID.randomUUID(),dimension=cut))))
            assertThat(total(warehouse)).isEqualTo(100000)
            assertThat(scalar("SELECT sum(reserved_unpicked_base+reserved_picked_base) FROM inventory_reservation")).isEqualTo("20000")
            assertThat(scalar("SELECT stock_identity_id FROM inventory_reservation WHERE state='OPEN'")).isEqualTo(cut.stockIdentityId.toString())
        }
    }

    @Test fun `cut and pick rebind the original reservation without a second encumbrance`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction {
            val piece=receipt(StockQuantity.metres("1000"))
            val reserve=reservation(piece,StockQuantity.metres("100"))
            post(reserve)
            val old=reserve.reservations.single()
            val cut=piece.copy(stockIdentityId=UUID.randomUUID())
            val rest=piece.copy(stockIdentityId=UUID.randomUUID())
            val split=PostingSplit(piece.stockIdentityId,0,listOf(
                SegmentChild(cut.stockIdentityId,StockQuantity.metres("100"),SegmentKind.CUT),
                SegmentChild(rest.stockIdentityId,StockQuantity.metres("900"),SegmentKind.REMNANT)))
            val command=move(piece,cut,StockQuantity.metres("100"),splits=listOf(split),extra=listOf(rest to StockQuantity.metres("900")))
            post(command.copy(reservations=listOf(old.copy(expectedRevision=0,dimension=cut,
                unpicked=StockQuantity.metres("0"),picked=StockQuantity.metres("100"),partitionFrom=old.id))))
            assertThat(scalar("SELECT count(*) FROM inventory_reservation")).isEqualTo("1")
            assertThat(scalar("SELECT stock_identity_id FROM inventory_reservation")).isEqualTo(cut.stockIdentityId.toString())
            assertThat(scalar("SELECT reserved_picked_base FROM inventory_reservation")).isEqualTo("100000")
            assertThat(total(warehouse)).isEqualTo(1000000)
        }
    }

    @Test fun `reservation partition cannot inflate or silently release reserved quantity`() {
        for (amount in listOf("99", "101")) {
            val fixture=WarehousePostingFixture(context).also { it.setup() }
            val piece=fixture.transaction { receipt(StockQuantity.metres("1000")) }
            val reserve=fixture.transaction { reservation(piece,StockQuantity.metres("100")).also { post(it) } }
            val old=reserve.reservations.single()
            val cut=piece.copy(stockIdentityId=UUID.randomUUID())
            val rest=piece.copy(stockIdentityId=UUID.randomUUID())
            val before=fixture.transaction { counts() }
            assertThatThrownBy {
                fixture.transaction {
                    val split=PostingSplit(piece.stockIdentityId,0,listOf(
                        SegmentChild(cut.stockIdentityId,StockQuantity.metres("100"),SegmentKind.CUT),
                        SegmentChild(rest.stockIdentityId,StockQuantity.metres("900"),SegmentKind.REMNANT)))
                    val command=move(piece,cut,StockQuantity.metres("100"),splits=listOf(split),extra=listOf(rest to StockQuantity.metres("900")))
                    post(command.copy(reservations=listOf(old.copy(expectedRevision=0,dimension=cut,
                        unpicked=StockQuantity.metres("0"),picked=StockQuantity.metres(amount),partitionFrom=old.id))))
                }
            }.hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            fixture.transaction {
                assertThat(counts()).isEqualTo(before)
                assertThat(scalar("SELECT stock_identity_id FROM inventory_reservation")).isEqualTo(piece.stockIdentityId.toString())
                assertThat(total(warehouse)).isEqualTo(1000000)
            }
        }
    }
}
