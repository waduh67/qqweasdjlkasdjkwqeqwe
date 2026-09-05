package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.inventory.application.service.InventoryMovementLedgerService
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class InventoryMovementLedgerTest {
    private val tenant = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val sku = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val warehouse = UUID.randomUUID()
    private val technician = UUID.randomUUID()

    private fun leg(direction: LegDirection, quantity: Int = 1, status: InventoryStatus = InventoryStatus.AVAILABLE, owner: UUID = actor, ownerKind: OwnerKind = OwnerKind.WAREHOUSE) = MovementLeg(direction, item, sku, warehouse, quantity, false, owner, ownerKind, status)
    private fun command(key: String, kind: MovementKind, legs: List<MovementLeg>, hash: String = key) = MovementCommand(tenant, actor, "inventory.$kind", key, hash, "test", kind, legs)

    @Test
    fun `receive reserve issue consume and return conserve ledger projection`() {
        val service = InventoryMovementLedgerService()
        service.apply(command("receive", MovementKind.RECEIVE, listOf(leg(LegDirection.IN, 4))))
        service.apply(command("reserve", MovementKind.RESERVE, listOf(leg(LegDirection.OUT, 2), leg(LegDirection.IN, 2, InventoryStatus.RESERVED))))
        service.apply(command("issue", MovementKind.ISSUE, listOf(leg(LegDirection.OUT, 1, InventoryStatus.RESERVED), leg(LegDirection.IN, 1, InventoryStatus.ISSUED, technician, OwnerKind.TECHNICIAN))))
        service.apply(command("consume", MovementKind.CONSUME, listOf(leg(LegDirection.OUT, 1, InventoryStatus.ISSUED, technician, OwnerKind.TECHNICIAN))))
        service.apply(command("return", MovementKind.RETURN, listOf(leg(LegDirection.IN, 1, InventoryStatus.RETURNED))))

        assertThat(service.balances(tenant)).allMatch { it.quantity >= 0 }
        assertThat(service.movements(tenant)).hasSize(5)
        assertThat(service.rebuild(tenant)).containsExactlyInAnyOrderElementsOf(service.balances(tenant))
    }

    @Test
    fun `negative balance serialized mismatch replay conflict and pending adjustment are deterministic`() {
        val service = InventoryMovementLedgerService()
        assertThatThrownBy { service.apply(command("issue", MovementKind.ISSUE, listOf(leg(LegDirection.OUT), leg(LegDirection.IN, owner = technician, ownerKind = OwnerKind.TECHNICIAN, status = InventoryStatus.ISSUED)))) }.isInstanceOf(InventoryInsufficientBalance::class.java)
        assertThatThrownBy { MovementLeg(LegDirection.IN, item, sku, warehouse, 2, true, actor, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE) }.isInstanceOf(IllegalArgumentException::class.java)
        val receive = service.apply(command("receive", MovementKind.RECEIVE, listOf(leg(LegDirection.IN))))
        assertThat(service.apply(command("receive", MovementKind.RECEIVE, listOf(leg(LegDirection.IN)))).movementId).isEqualTo(receive.movementId)
        assertThatThrownBy { service.apply(command("receive", MovementKind.RECEIVE, listOf(leg(LegDirection.IN)), "different")) }.isInstanceOf(ConflictException::class.java)
        val adjustment = service.apply(command("adjust", MovementKind.ADJUSTMENT, listOf(leg(LegDirection.OUT))))
        assertThat(adjustment.state).isEqualTo(MovementState.PENDING_APPROVAL)
    }

    @Test
    fun `transfer has paired legs and reversal appends opposite legs`() {
        val service = InventoryMovementLedgerService()
        service.apply(command("receive", MovementKind.RECEIVE, listOf(leg(LegDirection.IN))))
        val transfer = service.apply(command("transfer", MovementKind.TRANSFER, listOf(leg(LegDirection.OUT), leg(LegDirection.IN, owner = technician, ownerKind = OwnerKind.TRANSIT))))
        val reversal = service.reverse(transfer.movementId, command("reverse", MovementKind.REVERSAL, transfer.legs))
        assertThat(reversal.compensatesMovementId).isEqualTo(transfer.movementId)
        assertThat(service.movements(tenant)).hasSize(3)
    }

    @Test
    fun `concurrent same operation produces one movement`() {
        val service = InventoryMovementLedgerService()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val jobs = (1..2).map { pool.submit { start.await(); service.apply(command("same", MovementKind.RECEIVE, listOf(leg(LegDirection.IN)))) } }
        start.countDown()
        jobs.forEach { it.get() }
        pool.shutdown()
        assertThat(service.movements(tenant)).hasSize(1)
    }
}
