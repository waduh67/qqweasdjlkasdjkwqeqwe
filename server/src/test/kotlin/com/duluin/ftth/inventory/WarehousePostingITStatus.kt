package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import java.time.Instant
import org.mockito.Mockito

class WarehousePostingITStatus : WarehousePostingRegressionSupport() {
    @Test fun `AV5-01 recreated projection retains the inbound revision high water mark`() {
        val fixture=fixture()
        val piece=fixture.transaction {
            val received=receipt(StockQuantity.each("1"))
            post(reclassify(received,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED))
            received
        }
        repeat(3) { fixture.transaction { context.getBean(WarehousePosting::class.java).rebuild(0) } }
        fixture.transaction {
            post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.ISSUED,InventoryStatus.AVAILABLE))
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='${piece.stockIdentityId}'")
        }
        fixture.transaction {
            context.getBean(WarehousePosting::class.java).rebuild(0)
            post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED,true))
        }
        fixture.transaction {
            context.getBean(WarehousePosting::class.java).rebuild(0)
            assertThat(state(piece.stockIdentityId)).isEqualTo("ISSUED/ISSUED")
        }
    }

    @Test fun `AV5-01 tied clocks use durable dimension revisions rather than UUIDs`() {
        val fixture=fixture()
        val fixed=Instant.now()
        val identities=Mockito.mockStatic(Instant::class.java,Mockito.CALLS_REAL_METHODS).use { clock ->
            clock.`when`<Instant> { Instant.now() }.thenReturn(fixed)
            fixture.transaction {
                (1..16).map { index ->
                    val piece=receipt(StockQuantity.each("1"))
                    post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED,index%2==0))
                    post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.ISSUED,InventoryStatus.AVAILABLE,index%2!=0))
                    piece.stockIdentityId
                }
            }
        }
        fixture.transaction {
            assertThat(scalar("SELECT count(DISTINCT server_received_at) FROM inventory_movement")).isEqualTo("1")
            repeat(5) {
                context.getBean(WarehousePosting::class.java).rebuild(0)
                identities.forEach { assertThat(state(it)).isEqualTo("AVAILABLE/AVAILABLE") }
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `AV5-01 same dimension status is independent of leg order and rebuild`(reverse: Boolean) {
        val fixture=fixture()
        val identity=fixture.transaction {
            val piece=receipt(StockQuantity.each("1"))
            post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED,reverse))
            piece.stockIdentityId
        }
        repeat(8) {
            fixture.transaction {
                assertThat(state(identity)).isEqualTo("ISSUED/ISSUED")
                context.getBean(WarehousePosting::class.java).rebuild(0)
                assertThat(state(identity)).isEqualTo("ISSUED/ISSUED")
            }
        }
    }

    @Test fun `AV5-01 random leg ids and input orders cannot choose serial status`() {
        val fixture=fixture()
        val random=Random(951)
        val identities=fixture.transaction {
            (1..24).map {
                val piece=receipt(StockQuantity.each("1"))
                post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED,random.nextBoolean()))
                post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.ISSUED,InventoryStatus.AVAILABLE,random.nextBoolean()))
                post(reclassify(piece,StockQuantity.each("1"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED,random.nextBoolean()))
                piece.stockIdentityId
            }
        }
        repeat(4) { fixture.transaction {
            context.getBean(WarehousePosting::class.java).rebuild(0)
            identities.forEach { assertThat(state(it)).isEqualTo("ISSUED/ISSUED") }
        } }
    }

    @Test fun `AV5-01 conflicting inbound statuses are rejected without mutation`() {
        val fixture=fixture()
        val piece=fixture.transaction { receipt(StockQuantity.each("100"),true) }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            val command=reclassify(piece,StockQuantity.each("50"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED)
            val incoming=command.legs.single { it.direction==LegDirection.IN }.copy(quantity=StockQuantity.each("25"))
            post(command.copy(legs=command.legs.filter { it.direction==LegDirection.OUT }+incoming+incoming.copy(status=InventoryStatus.AVAILABLE)))
        } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `AV5-01 partial bulk outflow preserves the latest inbound status`() {
        val fixture=fixture()
        fixture.transaction {
            val piece=receipt(StockQuantity.each("100"),true)
            post(reclassify(piece,StockQuantity.each("20"),InventoryStatus.AVAILABLE,InventoryStatus.ISSUED,true))
            val target=piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            val command=move(piece,target,StockQuantity.each("30"))
            post(command.copy(legs=command.legs.map { it.copy(status=InventoryStatus.ISSUED) }))
            repeat(5) {
                context.getBean(WarehousePosting::class.java).rebuild(0)
                assertThat(scalar("SELECT status||':'||quantity_base FROM inventory_balance_projection WHERE stock_identity_id='${piece.stockIdentityId}' AND location_id='$warehouse'")).isEqualTo("ISSUED:70")
            }
        }
    }
}
