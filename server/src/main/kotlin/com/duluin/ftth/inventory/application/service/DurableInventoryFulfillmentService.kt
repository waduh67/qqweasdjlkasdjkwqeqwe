package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.inventory.InventoryFulfillmentResult
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryFulfillmentEffectJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryFulfillmentEffectJpaRepository
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementJpaRepository
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementLegJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementLegJpaRepository
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LegDirection
import com.duluin.ftth.inventory.domain.model.MovementKind
import com.duluin.ftth.inventory.domain.model.MovementState
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DurableInventoryFulfillmentService(
    private val effects: InventoryFulfillmentEffectJpaRepository,
    private val movements: InventoryMovementJpaRepository,
    private val legs: InventoryMovementLegJpaRepository,
) {
    @Transactional
    fun apply(command: InventoryFulfillmentCommand, returned: Boolean): InventoryFulfillmentResult {
        val prior = effects.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
        if (prior != null) {
            if (prior.payloadHash != command.payloadHash || prior.targetId != command.targetId) throw ConflictException("Operation key was used with a different payload")
            return InventoryFulfillmentResult(command.tenantId, command.operationKey, command.targetId, true, true, prior.recordedAt)
        }
        val now = Instant.now()
        val effectId = UUID.randomUUID()
        val inserted = effects.insertIfAbsent(effectId, command.tenantId, command.targetId, command.workOrderId, command.customerId, command.namespace, command.operationKey, command.payloadHash, command.itemCategory, command.quantity, command.installed, returned)
        if (inserted == 0) {
            val replay = effects.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
                ?: throw ConflictException("Inventory effect disappeared during concurrent insert")
            if (replay.payloadHash != command.payloadHash || replay.targetId != command.targetId) throw ConflictException("Operation key was used with a different payload")
            return InventoryFulfillmentResult(command.tenantId, command.operationKey, command.targetId, true, true, replay.recordedAt)
        }
        val movementId = UUID.randomUUID()
        movements.save(InventoryMovementJpaEntity(movementId, command.namespace, command.operationKey, command.payloadHash, command.actorId, command.reason, now, if (returned) MovementKind.RETURN else MovementKind.CONSUME, MovementState.APPLIED, null))
        legs.save(InventoryMovementLegJpaEntity(UUID.randomUUID(), movementId, if (returned) LegDirection.IN else LegDirection.OUT, command.itemId, command.skuId, command.locationId, command.quantity, command.serialized, command.actorId, OwnerKind.TECHNICIAN, if (returned) InventoryStatus.RETURNED else InventoryStatus.ISSUED))
        return InventoryFulfillmentResult(command.tenantId, command.operationKey, command.targetId, true, false, now)
    }
}
