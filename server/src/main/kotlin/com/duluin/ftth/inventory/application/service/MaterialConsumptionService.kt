package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.domain.model.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

class MaterialConsumptionService(
    private val ledger: InventoryMovementLedgerService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val monitor = Any()
    private val facts = linkedMapOf<OperationIdentity, RecordedFact>()

    fun consume(command: MaterialConsumptionCommand): CustomerMaterialFact = record(command, returned = false) {
        require(command.installed) { "consumption must describe installed material" }
        ledger.apply(command.toMovement(MovementKind.CONSUME, InventoryStatus.ISSUED, LegDirection.OUT))
    }

    fun returnUnused(command: MaterialConsumptionCommand): CustomerMaterialFact = record(command, returned = true) {
        require(!command.installed) { "returned material cannot be installed" }
        ledger.apply(command.toMovement(MovementKind.RETURN, InventoryStatus.RETURNED, LegDirection.IN))
    }

    fun forCustomer(tenantId: UUID, customerId: UUID): List<CustomerMaterialFact> = synchronized(monitor) {
        facts.values.map { it.fact }.filter { it.tenantId == tenantId && it.customerId == customerId }.toList()
    }

    fun hasRecorded(command: MaterialConsumptionCommand): Boolean = synchronized(monitor) {
        facts[OperationIdentity(command.tenantId, command.operationKey)]?.let { it.payloadHash == command.payloadHash } == true
    }

    private fun record(command: MaterialConsumptionCommand, returned: Boolean, movement: () -> Unit): CustomerMaterialFact = synchronized(monitor) {
        val identity = OperationIdentity(command.tenantId, command.operationKey)
        facts[identity]?.let { prior ->
            require(prior.payloadHash == command.payloadHash) { "operation key was used with a different payload" }
            require(prior.fact.customerId == command.customerId && prior.fact.workOrderId == command.workOrderId) { "operation key is bound to another work order" }
            return@synchronized prior.fact
        }
        movement()
        val fact = CustomerMaterialFact(command.tenantId, command.customerId, command.workOrderId, command.itemCategory, command.quantity, command.installed, returned, Instant.now(clock))
        facts[identity] = RecordedFact(fact, command.payloadHash)
        fact
    }

    private fun MaterialConsumptionCommand.toMovement(kind: MovementKind, status: InventoryStatus, direction: LegDirection) = MovementCommand(
        tenantId, actorId, "workorder.material.$workOrderId", operationKey, payloadHash, reason, kind,
        listOf(MovementLeg(direction, itemId, skuId, locationId, quantity, serialized, actorId, OwnerKind.TECHNICIAN, status)),
    )

    private data class OperationIdentity(val tenantId: UUID, val key: String)
    private data class RecordedFact(val fact: CustomerMaterialFact, val payloadHash: String)
}
