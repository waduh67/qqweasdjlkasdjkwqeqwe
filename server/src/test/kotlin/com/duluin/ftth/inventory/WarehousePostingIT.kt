package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePostingIT {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }

    @Test fun `receive issue consume return inspect conserve exact physical quantities`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction {
            val reel=receipt(StockQuantity.metres("1000"))
            val serials=(1..10).map { receipt(StockQuantity.each("1")) }
            val issued=reel.copy(stockIdentityId=UUID.randomUUID(),locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            val remainder=reel.copy(stockIdentityId=UUID.randomUUID())
            post(move(reel,issued,StockQuantity.metres("100"),splits=listOf(PostingSplit(reel.stockIdentityId,0,listOf(
                SegmentChild(issued.stockIdentityId,StockQuantity.metres("100"),SegmentKind.CUT),
                SegmentChild(remainder.stockIdentityId,StockQuantity.metres("900"),SegmentKind.REMNANT)))),extra=listOf(remainder to StockQuantity.metres("900"))))
            val onu=serials.first().copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(serials.first(),onu,StockQuantity.each("1")))
            val used=issued.copy(stockIdentityId=UUID.randomUUID(),locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER)
            val remnant=issued.copy(stockIdentityId=UUID.randomUUID())
            val fact=PostingMaterialFact(UUID.randomUUID(),used.stockIdentityId,customer,workOrder,"Cable",StockQuantity.metres("82.5"),1,true,false)
            post(move(issued,used,StockQuantity.metres("82.5"),MovementKind.CONSUME,listOf(PostingSplit(issued.stockIdentityId,0,listOf(
                SegmentChild(used.stockIdentityId,StockQuantity.metres("82.5"),SegmentKind.CUT),
                SegmentChild(remnant.stockIdentityId,StockQuantity.metres("17.5"),SegmentKind.REMNANT)))),listOf(remnant to StockQuantity.metres("17.5")),listOf(fact)))
            val installed=onu.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER)
            post(move(onu,installed,StockQuantity.each("1"),MovementKind.CONSUME,facts=listOf(
                PostingMaterialFact(UUID.randomUUID(),onu.stockIdentityId,customer,workOrder,"ONU",StockQuantity.each("1"),2,true,false))))
            val inspection=remnant.copy(locationId=warehouse,custodianId=warehouse,custodianKind=OwnerKind.WAREHOUSE,condition=WarehouseCondition.QUARANTINE)
            post(move(remnant,inspection,StockQuantity.metres("17.5"),MovementKind.RETURN))
            post(move(inspection,inspection.copy(condition=WarehouseCondition.SERVICEABLE),StockQuantity.metres("17.5")))
            assertThat(total(warehouse)).isEqualTo(917500)
            assertThat(total(warehouse,StockUnit.EA)).isEqualTo(9)
            assertThat(total(consumed)).isEqualTo(82500)
            assertThat(total(technician)).isZero()
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${onu.stockIdentityId}' AND quantity_base>0")).isEqualTo("1")
            assertThat(scalar("SELECT coalesce(sum(CASE direction WHEN 'IN' THEN quantity_base::numeric ELSE -quantity_base::numeric END),0) FROM inventory_movement_leg")).isEqualTo("0")
        }
        val history=fixture.transaction { counts() }
        fixture.transaction { context.getBean(WarehousePosting::class.java).rebuild(0); assertThat(total(warehouse)).isEqualTo(917500); assertThat(total(consumed)).isEqualTo(82500) }
        postingContext(database).use { fresh -> WarehousePostingFixture(fresh,fixture.tenant).transaction {
            assertThat(counts()).isEqualTo(history)
            assertThat(total(fixture.warehouse)).isEqualTo(917500)
            assertThat(total(fixture.warehouse,StockUnit.EA)).isEqualTo(9)
            assertThat(total(fixture.consumed)).isEqualTo(82500)
            assertThat(total(fixture.technician)).isZero()
        } }
    }

    @ParameterizedTest @EnumSource(TestPostingPhase::class)
    fun `failure at every persistence phase rolls back all effects`(phase: TestPostingPhase) {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction {
            val received=receipt(StockQuantity.each("1"))
            val issued=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            val command=move(received,issued,StockQuantity.each("1"))
            post(command.copy(legs=command.legs.map { if(it.direction==LegDirection.IN) it.copy(status=InventoryStatus.AVAILABLE) else it }))
            issued
        }
        val reservation=fixture.transaction { val command=reservation(piece,StockQuantity.each("1")); post(command); command.reservations.single() }
        fixture.transaction { post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED)) }
        val before=fixture.transaction { counts() }
        val occurrence=if(phase in setOf(TestPostingPhase.LEGS,TestPostingPhase.BALANCES)) 2 else 1
        PostingJdbcProbe(context,phase,occurrence) { error("injected-$phase") }.use { probe ->
            assertThatThrownBy { fixture.transaction {
                val fact=PostingMaterialFact(UUID.randomUUID(),piece.stockIdentityId,customer,workOrder,"ONU",StockQuantity.each("1"),1,true,false,UUID.randomUUID())
                val command=move(piece,piece.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),StockQuantity.each("1"),MovementKind.CONSUME,facts=listOf(fact))
                post(command.copy(reservations=listOf(reservation.copy(expectedRevision=0,unpicked=StockQuantity.each("0"),state=ReservationState.DISPATCHED)),usage=usage(piece)))
            } }.hasMessageContaining("injected")
            assertThat(probe.observations).isEqualTo(occurrence)
        }
        fixture.transaction {
            assertThat(counts()).isEqualTo(before); assertThat(total(technician,StockUnit.EA)).isEqualTo(1)
            assertThat(scalar("SELECT reserved_unpicked_base FROM inventory_reservation")).isEqualTo("1")
            assertThat(scalar("SELECT location_id FROM inventory_serialized_asset")).isEqualTo(technician.toString())
        }
    }

    @Test fun `rebuild and fresh application context preserve committed totals and history`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction { receipt(StockQuantity.metres("1000")) }
        val before=fixture.transaction { counts() }
        fixture.transaction { sql("UPDATE inventory_balance_projection SET quantity_base=999000,revision=revision+1") }
        val rebuilt=fixture.transaction { context.getBean(WarehousePosting::class.java).rebuild(0) }
        assertThat(rebuilt.single().quantity).isEqualTo(StockQuantity.metres("1000"))
        postingContext(database).use { fresh ->
            WarehousePostingFixture(fresh,fixture.tenant).transaction {
                assertThat(counts()).isEqualTo(before)
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("1000000")
            }
        }
    }

    @Test fun `supplied usage snapshot facts operation and event commit with the same posting`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val result=fixture.transaction {
            val received=receipt(StockQuantity.each("1"))
            val issued=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(received,issued,StockQuantity.each("1")))
            val target=issued.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER)
            val fact=PostingMaterialFact(UUID.randomUUID(),issued.stockIdentityId,customer,workOrder,"ONU",StockQuantity.each("1"),1,true,false)
            post(move(issued,target,StockQuantity.each("1"),MovementKind.CONSUME,facts=listOf(fact)).copy(usage=usage(issued)))
        }
        fixture.transaction {
            assertThat(scalar("SELECT posting_ids[1] FROM inventory_usage_snapshot")).isEqualTo(result.postingId.toString())
            assertThat(scalar("SELECT operation_id FROM inventory_usage_snapshot")).isEqualTo(result.operationId.toString())
            assertThat(scalar("SELECT posting_id FROM inventory_customer_material_fact")).isEqualTo(result.postingId.toString())
            assertThat(scalar("SELECT count(*) FROM inventory_outbox WHERE operation_id='${result.operationId}'")).isEqualTo("1")
        }
    }
}
