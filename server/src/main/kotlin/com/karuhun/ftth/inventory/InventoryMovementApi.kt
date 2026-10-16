package com.karuhun.ftth.inventory

import com.karuhun.ftth.inventory.application.service.DurableInventoryFulfillmentService
import com.karuhun.ftth.inventory.domain.model.InventoryMovement
import com.karuhun.ftth.inventory.domain.model.MovementCommand
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
    private val fulfillment: DurableInventoryFulfillmentService,
) : InventoryMovementApi {
    override fun consume(command: MovementCommand): InventoryMovement {
        require(command.kind.name == "CONSUME") { "consumption API accepts CONSUME movements only" }
        return fulfillment.consumeMovement(command)
    }
}
