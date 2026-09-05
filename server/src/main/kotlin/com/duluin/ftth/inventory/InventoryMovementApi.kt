package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.InventoryMovementLedgerService
import com.duluin.ftth.inventory.domain.model.InventoryMovement
import com.duluin.ftth.inventory.domain.model.MovementCommand
import java.util.UUID

interface InventoryMovementApi {
    fun consume(command: MovementCommand): InventoryMovement
}

data class WorkOrderInventoryConsumed(
    val tenantId: UUID,
    val workOrderId: UUID,
    val movementId: UUID,
    val operationKey: String,
    val serverReceivedAt: java.time.Instant,
)

class InventoryMovementApiAdapter(
    private val ledger: InventoryMovementLedgerService,
) : InventoryMovementApi {
    override fun consume(command: MovementCommand): InventoryMovement {
        require(command.kind.name == "CONSUME") { "consumption API accepts CONSUME movements only" }
        return ledger.apply(command)
    }
}
