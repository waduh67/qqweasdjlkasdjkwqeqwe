package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.InventoryMovementLedgerService
import com.duluin.ftth.inventory.application.service.InventoryReconciliationService
import com.duluin.ftth.inventory.application.service.MaterialConsumptionService
import com.duluin.ftth.inventory.application.service.CycleCountCommand
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class InventoryMaterialAndReconciliationTest {
    private val tenant = UUID.randomUUID()
    private val otherTenant = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val workOrder = UUID.randomUUID()
    private val customer = UUID.randomUUID()
    private val sku = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val location = UUID.randomUUID()

    @Test
    fun `issued material is consumed once and returned material is customer safe`() {
        val ledger = InventoryMovementLedgerService()
        ledger.apply(movement("receive", MovementKind.RECEIVE, MovementLeg(LegDirection.IN, item, sku, location, 2, false, actor, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE)))
        ledger.apply(movement("issue", MovementKind.ISSUE, MovementLeg(LegDirection.OUT, item, sku, location, 1, false, actor, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE), MovementLeg(LegDirection.IN, item, sku, location, 1, false, actor, OwnerKind.TECHNICIAN, InventoryStatus.ISSUED)))
        val service = MaterialConsumptionService(ledger)
        val command = material("consume", installed = true)

        val first = service.consume(command)
        val replay = service.consume(command)

        assertThat(replay).isEqualTo(first)
        assertThat(service.forCustomer(tenant, customer)).extracting<String> { it.itemCategory }
            .containsExactly("ONT")
        assertThat(service.forCustomer(otherTenant, customer)).isEmpty()
        assertThatThrownBy { service.consume(command.copy(payloadHash = "different")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `count variance records evidence and custodian cannot approve`() {
        val ledger = InventoryMovementLedgerService()
        ledger.apply(movement("receive", MovementKind.RECEIVE, MovementLeg(LegDirection.IN, item, sku, location, 5, false, actor, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE)))
        val reconciliation = InventoryReconciliationService(ledger)
        val count = reconciliation.createCount(CycleCountCommand(tenant, tenant, location, item, sku, 3, actor, "damaged two", "evidence://count-1", "count-1", "count-hash"))

        assertThat(count.priorQuantity).isEqualTo(5)
        assertThat(count.observedQuantity).isEqualTo(3)
        assertThatThrownBy { reconciliation.approveVariance(count.countId, actor, "approve", "approve-hash") }
            .hasMessageContaining("custodian")
        val closed = reconciliation.approveVariance(count.countId, UUID.randomUUID(), "approve", "approve-hash")
        assertThat(closed.discrepancy).isEqualTo(DiscrepancyState.RESOLVED)
        assertThat(closed.closedAt).isNotNull()
        assertThat(ledger.movements(tenant)).hasSize(2)
    }

    private fun material(key: String, installed: Boolean) = MaterialConsumptionCommand(
        tenant, workOrder, customer, actor, key, key, sku, item, location, 1, false, "field work", "ONT", installed,
    )

    private fun movement(key: String, kind: MovementKind, vararg legs: MovementLeg) = MovementCommand(
        tenant, actor, "test.$kind", key, key, "test", kind, legs.toList(),
    )
}
