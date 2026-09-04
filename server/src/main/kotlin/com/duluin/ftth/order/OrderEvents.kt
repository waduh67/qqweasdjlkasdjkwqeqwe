package com.duluin.ftth.order

import java.time.Instant
import java.util.UUID

sealed interface OrderEvent {
    val orderId: UUID
    val tenantId: UUID
    val revision: Long
    val actorId: UUID?
    val operation: OperationCommand
    val at: Instant
}

data class OrderStateChanged(
    override val orderId: UUID,
    override val tenantId: UUID,
    override val revision: Long,
    val from: String,
    val to: String,
    override val actorId: UUID?,
    override val operation: OperationCommand,
    override val at: Instant,
) : OrderEvent

data class OrderCreated(
    override val orderId: UUID,
    override val tenantId: UUID,
    override val revision: Long,
    override val actorId: UUID?,
    override val operation: OperationCommand,
    override val at: Instant,
) : OrderEvent
