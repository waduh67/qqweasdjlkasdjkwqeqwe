package com.duluin.ftth.inventory.domain.model

import java.time.Instant
import java.util.UUID

enum class MovementKind {
    RESTOCK, RECEIVE, RESERVE, RELEASE, ISSUE, ISSUE_EXCEPTION, TRANSFER, TRANSFER_RECEIPT, RETURN,
    REPAIR, QUARANTINE, ADJUSTMENT, LOSS, SCRAP, WRITE_OFF, COUNT_VARIANCE, DISPOSAL, CONSUME, REVERSAL,
}

enum class LegDirection { IN, OUT }
enum class MovementState { APPLIED, PENDING_APPROVAL, FAILED_PERMANENT, REQUIRES_MANUAL_REPAIR }

data class MovementLeg(
    val direction: LegDirection,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
) {
    init {
        require(quantity > 0) { "movement leg quantity must be positive" }
        require(!serialized || quantity == 1) { "serialized movement quantity must be one" }
    }
}

data class InventoryMovement(
    val movementId: UUID,
    val tenantId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val actorId: UUID,
    val reason: String,
    val serverReceivedAt: Instant,
    val kind: MovementKind,
    val legs: List<MovementLeg>,
    val state: MovementState,
    val compensatesMovementId: UUID? = null,
) {
    init {
        require(namespace.isNotBlank() && operationKey.isNotBlank()) { "operation identity is required" }
        require(payloadHash.isNotBlank()) { "payload hash is required" }
        require(reason.isNotBlank()) { "movement reason is required" }
        require(legs.isNotEmpty()) { "movement must have legs" }
        require(legs.count { it.direction == LegDirection.IN } == legs.count { it.direction == LegDirection.OUT } || kind in setOf(MovementKind.RECEIVE, MovementKind.RETURN, MovementKind.REPAIR, MovementKind.QUARANTINE, MovementKind.DISPOSAL, MovementKind.CONSUME, MovementKind.ADJUSTMENT, MovementKind.COUNT_VARIANCE)) {
            "paired movement must have balanced IN and OUT legs"
        }
    }
}

data class InventoryBalance(
    val tenantId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val quantity: Int,
)

data class MovementCommand(
    val tenantId: UUID,
    val actorId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val reason: String,
    val kind: MovementKind,
    val legs: List<MovementLeg>,
    val compensatesMovementId: UUID? = null,
)

class InventoryMovementConflict(message: String) : IllegalArgumentException(message)
class InventoryInsufficientBalance(message: String) : IllegalStateException(message)
