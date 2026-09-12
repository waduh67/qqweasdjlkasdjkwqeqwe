package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.*
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderCustomerProjection
import com.duluin.ftth.order.application.port.outbound.OrderNumberGenerator
import com.duluin.ftth.order.application.port.outbound.OrderOutboxStore
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Suppress("LongParameterList")
class OrderApplicationService(
    private val orders: OrderRepository,
    private val currentUser: CurrentUserProvider,
    private val projection: OrderCustomerProjection,
    private val numbers: OrderNumberGenerator,
    private val audit: OrderAuditStore,
    private val outbox: OrderOutboxStore,
) : OrderApi {

    @Transactional
    override fun create(command: CreateOrderCommand): OrderView {
        val user = currentUser.current()
        return replayOrConflict(user.tenantId, command.operation) {
            val now = Instant.now()
            val order = Order.create(command, user.tenantId, user.userId, numbers.next(user.tenantId, now))
            orders.save(order)
            record(order, previous = null, reason = null, eventType = ORDER_CREATED, at = now)
            outbox.enqueue(OrderCreated(order.id, order.tenantId, order.revision, user.userId, command.operation, now))
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
            val now = Instant.now()
            order.transition(command, user.userId)
            orders.save(order)
            record(order, previous, command.reason, ORDER_STATE_CHANGED, now)
            outbox.enqueue(
                OrderStateChanged(
                    order.id, order.tenantId, order.revision, previous, order.status.name,
                    user.userId, command.operation, now, command.reason,
                ),
            )
            toView(order)
        }
    }

    @Transactional(readOnly = true)
    override fun find(id: UUID): OrderView? {
        val user = currentUser.current()
        return orders.find(id)?.takeIf { it.tenantId == user.tenantId }?.let(::toView)
    }

    @Transactional(readOnly = true)
    override fun fulfillmentRevision(orderId: UUID): Long? = orders.findForFulfillment(orderId)?.revision

    @Transactional(readOnly = true)
    override fun portalOrders(customerId: UUID): List<PortalOrderView> =
        projection.findByCustomer(currentUser.current().tenantId, customerId)

    @Transactional(readOnly = true)
    override fun portalOrder(customerId: UUID, orderId: UUID): PortalOrderView? =
        projection.find(currentUser.current().tenantId, customerId, orderId)

    @Transactional
    override fun applyFulfillment(command: OrderFulfillmentCommand): OrderFulfillmentResult {
        require(command.transition == OrderTransition.START_FULFILLING || command.transition == OrderTransition.FULFILL) {
            "FULFILLMENT_ORDER_TRANSITION_NOT_ALLOWED"
        }
        val user = currentUser.current()
        if (user.tenantId != command.tenantId) throw NotFoundException("Order tidak ditemukan")
        val operation = OperationCommand(command.namespace, command.operationKey, command.payloadHash)
        val replayed = orders.findOutcome(command.tenantId, command.namespace, command.operationKey) != null
        val view = replayOrConflict(command.tenantId, operation) {
            val order = orders.findForFulfillment(command.orderId) ?: throw NotFoundException("Order tidak ditemukan")
            if (order.tenantId != command.tenantId) throw NotFoundException("Order tidak ditemukan")
            val previous = order.status.name
            val now = Instant.now()
            order.transition(
                OrderTransitionCommand(
                    orderId = command.orderId,
                    transition = command.transition,
                    expectedRevision = command.expectedRevision ?: order.revision,
                    operation = operation,
                ),
                user.userId,
            )
            orders.save(order)
            record(order, previous, reason = null, eventType = ORDER_STATE_CHANGED, at = now)
            outbox.enqueue(
                OrderStateChanged(
                    order.id, order.tenantId, order.revision, previous, order.status.name,
                    user.userId, operation, now,
                ),
            )
            toView(order)
        }
        return OrderFulfillmentResult(command.tenantId, command.orderId, view.status, view.revision, replayed)
    }

    /**
     * Riwayat ditulis di transaksi yang SAMA dengan perubahan agregat. Kalau dipisah ke listener
     * after-commit, sebuah transisi yang berhasil tapi listener-nya gagal akan meninggalkan
     * pesanan tanpa jejak — dan justru transisi bermasalah itulah yang paling perlu terlacak.
     */
    private fun record(order: Order, previous: String?, reason: String?, eventType: String, at: Instant) {
        audit.append(
            OrderAuditEntry(
                tenantId = order.tenantId,
                orderId = order.id,
                revision = order.revision,
                eventType = eventType,
                fromStatus = previous,
                toStatus = order.status.name,
                reason = reason,
                actorId = order.lastActorId,
                operationNamespace = order.lastOperation.namespace,
                operationKey = order.lastOperation.key,
                payloadHash = order.lastOperation.payloadHash,
                occurredAt = at,
            ),
        )
    }

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
        order.leadId, order.orderNumber,
    )

    companion object {
        const val ORDER_CREATED = "ORDER_CREATED"
        const val ORDER_STATE_CHANGED = "ORDER_STATE_CHANGED"
    }
}
