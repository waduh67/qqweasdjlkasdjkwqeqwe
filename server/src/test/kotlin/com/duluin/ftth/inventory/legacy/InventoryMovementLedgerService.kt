package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.inventory.domain.model.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

class InventoryMovementLedgerService(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val monitor = Any()
    private val movements = mutableListOf<InventoryMovement>()
    private val outcomes = mutableMapOf<OperationIdentity, InventoryMovement>()

    fun apply(command: MovementCommand): InventoryMovement = synchronized(monitor) {
        val identity = OperationIdentity(command.tenantId, command.namespace, command.operationKey)
        val prior = outcomes[identity]
        if (prior != null) {
            if (prior.payloadHash != command.payloadHash) throw ConflictException("operation key was used with a different payload")
            return@synchronized prior
        }
        require(command.legs.all { it.quantity > 0 }) { "movement quantity must be positive" }
        val state = if (command.kind in setOf(MovementKind.RESTOCK, MovementKind.ISSUE_EXCEPTION, MovementKind.ADJUSTMENT, MovementKind.LOSS, MovementKind.SCRAP, MovementKind.WRITE_OFF, MovementKind.COUNT_VARIANCE)) MovementState.PENDING_APPROVAL else MovementState.APPLIED
        val movement = InventoryMovement(UUID.randomUUID(), command.tenantId, command.namespace, command.operationKey, command.payloadHash, command.actorId, command.reason, Instant.now(clock), command.kind, command.legs, state, command.compensatesMovementId)
        if (state == MovementState.APPLIED) validateProjection(movement)
        movements += movement
        outcomes[identity] = movement
        movement
    }

    fun reverse(originalMovementId: UUID, command: MovementCommand): InventoryMovement = synchronized(monitor) {
        val original = movements.firstOrNull { it.movementId == originalMovementId } ?: error("movement does not exist")
        require(original.state == MovementState.APPLIED) { "only applied movement can be reversed" }
        val reversed = command.copy(kind = MovementKind.REVERSAL, legs = original.legs.map { it.copy(direction = if (it.direction == LegDirection.IN) LegDirection.OUT else LegDirection.IN) }, compensatesMovementId = originalMovementId)
        apply(reversed)
    }

    fun approvePending(movementId: UUID): InventoryMovement = synchronized(monitor) {
        val index = movements.indexOfFirst { it.movementId == movementId }
        require(index >= 0) { "movement does not exist" }
        val pending = movements[index]
        require(pending.state == MovementState.PENDING_APPROVAL) { "movement is not pending approval" }
        validateProjection(pending.copy(state = MovementState.APPLIED))
        val applied = pending.copy(state = MovementState.APPLIED)
        movements[index] = applied
        outcomes[OperationIdentity(applied.tenantId, applied.namespace, applied.operationKey)] = applied
        applied
    }

    fun rejectPending(movementId: UUID): InventoryMovement = synchronized(monitor) {
        val index = movements.indexOfFirst { it.movementId == movementId }
        require(index >= 0) { "movement does not exist" }
        val pending = movements[index]
        require(pending.state == MovementState.PENDING_APPROVAL) { "movement is not pending approval" }
        val rejected = pending.copy(state = MovementState.FAILED_PERMANENT)
        movements[index] = rejected
        outcomes[OperationIdentity(rejected.tenantId, rejected.namespace, rejected.operationKey)] = rejected
        rejected
    }

    fun movements(tenantId: UUID): List<InventoryMovement> = synchronized(monitor) { movements.filter { it.tenantId == tenantId }.toList() }

    fun balances(tenantId: UUID): List<InventoryBalance> = project(tenantId)

    fun rebuild(tenantId: UUID): List<InventoryBalance> = project(tenantId)

    private fun project(tenantId: UUID): List<InventoryBalance> {
        val result = mutableMapOf<BalanceKey, Int>()
        movements.filter { it.tenantId == tenantId && it.state == MovementState.APPLIED }.forEach { movement ->
            movement.legs.forEach { leg ->
                val key = BalanceKey(tenantId, leg.itemId, leg.skuId, leg.locationId, leg.custodyOwnerId, leg.custodyOwnerKind, leg.status)
                result[key] = (result[key] ?: 0) + if (leg.direction == LegDirection.IN) leg.quantity else -leg.quantity
            }
        }
        return result.filterValues { it != 0 }.map { (key, quantity) -> InventoryBalance(key.tenantId, key.itemId, key.skuId, key.locationId, key.ownerId, key.ownerKind, key.status, quantity) }
    }

    private fun validateProjection(candidate: InventoryMovement) {
        val current = project(candidate.tenantId).associateBy({ BalanceKey(it.tenantId, it.itemId, it.skuId, it.locationId, it.custodyOwnerId, it.custodyOwnerKind, it.status) }, InventoryBalance::quantity).toMutableMap()
        candidate.legs.forEach { leg ->
            val key = BalanceKey(candidate.tenantId, leg.itemId, leg.skuId, leg.locationId, leg.custodyOwnerId, leg.custodyOwnerKind, leg.status)
            current[key] = (current[key] ?: 0) + if (leg.direction == LegDirection.IN) leg.quantity else -leg.quantity
            if (current[key]!! < 0) throw InventoryInsufficientBalance("movement would create a negative balance")
        }
    }

    private data class OperationIdentity(val tenantId: UUID, val namespace: String, val key: String)
    private data class BalanceKey(val tenantId: UUID, val itemId: UUID, val skuId: UUID, val locationId: UUID, val ownerId: UUID, val ownerKind: OwnerKind, val status: InventoryStatus)
}
