package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.*
import com.duluin.ftth.order.adapter.outbound.persistence.InMemoryOrderCustomerProjection
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrderApplicationService(
    private val orders: OrderRepository,
    private val currentUser: CurrentUserProvider,
    private val projection: InMemoryOrderCustomerProjection,
) : OrderApi {
    private val eventLog = mutableListOf<OrderEvent>()

    @Transactional
    override fun create(command: CreateOrderCommand): OrderView {
        val user = currentUser.current()
        return replayOrConflict(user.tenantId, command.operation) {
            val order = Order.create(command, user.tenantId, user.userId)
            orders.save(order)
            projection.acceptCreated(order)
            eventLog += OrderCreated(order.id, order.tenantId, order.revision, user.userId, command.operation, java.time.Instant.now())
            toView(order)
        }
    }

    @Transactional
    override fun transition(command: OrderTransitionCommand): OrderView {
        val user = currentUser.current()
        return replayOrConflict(user.tenantId, command.operation) {
            val order = orders.find(command.orderId) ?: throw NotFoundException("Order tidak ditemukan")
            if (order.tenantId != user.tenantId) throw NotFoundException("Order tidak ditemukan")
            val previous = order.status.name
            order.transition(command, user.userId)
            orders.save(order)
            projection.acceptStateChanged(order)
            eventLog += OrderStateChanged(order.id, order.tenantId, order.revision, previous, order.status.name, user.userId, command.operation, java.time.Instant.now())
            toView(order)
        }
    }

    override fun find(id: UUID): OrderView? {
        val user = currentUser.current()
        return orders.find(id)?.takeIf { it.tenantId == user.tenantId }?.let(::toView)
    }

    @Transactional(readOnly = true)
    override fun fulfillmentRevision(orderId: UUID): Long? = orders.findForFulfillment(orderId)?.revision

    override fun portalOrders(customerId: UUID): List<PortalOrderView> = projection.findByCustomer(customerId)

    override fun portalOrder(customerId: UUID, orderId: UUID): PortalOrderView? =
        projection.findByCustomer(customerId).firstOrNull { it.id == orderId }

    @Transactional
    override fun applyFulfillment(command: OrderFulfillmentCommand): OrderFulfillmentResult {
        require(command.transition == OrderTransition.START_FULFILLING || command.transition == OrderTransition.FULFILL) {
            "FULFILLMENT_ORDER_TRANSITION_NOT_ALLOWED"
        }
        val user = currentUser.current()
        if (user.tenantId != command.tenantId) throw NotFoundException("Order tidak ditemukan")
        val replayed = orders.findOutcome(command.tenantId, command.namespace, command.operationKey) != null
        val view = replayOrConflict(command.tenantId, OperationCommand(command.namespace, command.operationKey, command.payloadHash)) {
            val order = orders.findForFulfillment(command.orderId) ?: throw NotFoundException("Order tidak ditemukan")
            if (order.tenantId != command.tenantId) throw NotFoundException("Order tidak ditemukan")
            order.transition(
                OrderTransitionCommand(
                    orderId = command.orderId,
                    transition = command.transition,
                    expectedRevision = command.expectedRevision ?: order.revision,
                    operation = OperationCommand(command.namespace, command.operationKey, command.payloadHash),
                ),
                user.userId,
            )
            orders.save(order)
            projection.acceptStateChanged(order)
            toView(order)
        }
        return OrderFulfillmentResult(command.tenantId, command.orderId, view.status, view.revision, replayed)
    }

    fun publishedEvents(): List<OrderEvent> = synchronized(eventLog) { eventLog.toList() }

    private fun replayOrConflict(tenantId: UUID, operation: OperationCommand, effect: () -> OrderView): OrderView {
        require(operation.namespace.isNotBlank() && operation.key.isNotBlank() && operation.payloadHash.isNotBlank())
        val prior = orders.findOutcome(tenantId, operation.namespace, operation.key)
        if (prior != null) {
            if (prior.hash != operation.payloadHash) throw ConflictException("Operation key sudah dipakai untuk payload berbeda")
            return prior.value
        }
        val result = effect()
        orders.saveOutcome(tenantId, operation.namespace, operation.key, operation.payloadHash, result)
        return result
    }

    private fun toView(order: Order) = OrderView(
        order.id, order.tenantId, order.customerId, order.status.name,
        order.lines.map { OrderLineView(it.catalogItemId, it.description, it.quantity) }, order.serviceAddress,
        order.appointment, order.cancellationReason, order.rejectionReason, order.revision, order.lastActorId,
        order.lastOperation.namespace, order.lastOperation.key, order.lastOperation.payloadHash,
    )

}
