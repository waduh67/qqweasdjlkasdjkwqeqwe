package com.duluin.ftth.order

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Public order boundary. Subscription ownership remains in customer. */
interface OrderApi {
    fun create(command: CreateOrderCommand): OrderView
    fun transition(command: OrderTransitionCommand): OrderView
    fun find(id: UUID): OrderView?
    fun portalOrders(customerId: UUID): List<PortalOrderView>
    fun portalOrder(customerId: UUID, orderId: UUID): PortalOrderView?
    fun applyFulfillment(command: OrderFulfillmentCommand): OrderFulfillmentResult
    fun fulfillmentRevision(orderId: UUID): Long?
}

data class OrderFulfillmentCommand(
    val tenantId: UUID,
    val orderId: UUID,
    val transition: OrderTransition,
    val expectedRevision: Long? = null,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
)

data class OrderFulfillmentResult(
    val tenantId: UUID,
    val orderId: UUID,
    val status: String,
    val revision: Long,
    val replayed: Boolean,
)

data class CreateOrderCommand(
    val customerId: UUID,
    val lines: List<OrderLineCommand>,
    val serviceAddress: ServiceAddress,
    val appointment: Appointment? = null,
    val operation: OperationCommand,
)

data class OrderLineCommand(val catalogItemId: UUID, val description: String, val quantity: Int)

data class ServiceAddress(
    val address: String,
    val city: String,
    val postalCode: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class Appointment(val startsAt: Instant, val endsAt: Instant)

data class OperationCommand(
    val namespace: String,
    val key: String,
    val payloadHash: String,
)

data class OrderTransitionCommand(
    val orderId: UUID,
    val transition: OrderTransition,
    val expectedRevision: Long,
    val reason: String? = null,
    val appointment: Appointment? = null,
    val operation: OperationCommand,
)

enum class OrderTransition { SUBMIT, ACCEPT, SCHEDULE, START_FULFILLING, FULFILL, CANCEL, REJECT }

data class OrderView(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
    val status: String,
    val lines: List<OrderLineView>,
    val serviceAddress: ServiceAddress,
    val appointment: Appointment?,
    val cancellationReason: String?,
    val rejectionReason: String?,
    val revision: Long,
    val lastActorId: UUID?,
    val lastOperationNamespace: String,
    val lastOperationKey: String,
    val lastOperationHash: String,
)

data class OrderLineView(val catalogItemId: UUID, val description: String, val quantity: Int)

/** Customer-safe contract. It intentionally has no technician, GPS, evidence, or approval data. */
data class PortalOrderView(
    val id: UUID,
    val status: PortalOrderStatus,
    val lines: List<OrderLineView>,
    val serviceAddress: ServiceAddress,
    val appointment: Appointment?,
    val revision: Long,
)

enum class PortalOrderStatus { RECEIVED, REVIEWING, SCHEDULED, IN_PROGRESS, WAITING_CUSTOMER, COMPLETED, CANCELLED, REQUIRES_ATTENTION }
