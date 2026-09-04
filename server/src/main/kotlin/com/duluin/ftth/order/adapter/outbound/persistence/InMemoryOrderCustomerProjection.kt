package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.order.*
import com.duluin.ftth.order.domain.model.Order
import com.duluin.ftth.order.domain.model.OrderStatus
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryOrderCustomerProjection {
    private val rows = ConcurrentHashMap<UUID, PortalRow>()
    private val safeStatuses = ConcurrentHashMap<UUID, PortalOrderStatus>()

    fun acceptCreated(order: Order) = accept(order)
    fun acceptStateChanged(order: Order) = accept(order)

    fun attentionRequired(orderId: UUID) {
        rows[orderId]?.let { row ->
            safeStatuses.putIfAbsent(orderId, row.view.status)
            rows[orderId] = row.copy(view = row.view.copy(status = PortalOrderStatus.REQUIRES_ATTENTION))
        }
    }

    fun attentionCleared(orderId: UUID) {
        rows[orderId]?.let { row ->
            val status = safeStatuses.remove(orderId) ?: return@let
            rows[orderId] = row.copy(view = row.view.copy(status = status))
        }
    }

    fun findByCustomer(customerId: UUID): List<PortalOrderView> = rows.values
        .filter { it.customerId == customerId }
        .sortedBy { it.view.id }
        .map { it.view }

    private fun accept(order: Order) {
        val status = portalStatus(order.status) ?: run { rows.remove(order.id); return }
        rows[order.id] = PortalRow(order.customerId, PortalOrderView(
            order.id, status, order.lines.map { OrderLineView(it.catalogItemId, it.description, it.quantity) },
            order.serviceAddress, order.appointment, order.revision,
        ))
    }

    private fun portalStatus(status: OrderStatus): PortalOrderStatus? = when (status) {
        OrderStatus.DRAFT -> null
        OrderStatus.SUBMITTED -> PortalOrderStatus.RECEIVED
        OrderStatus.ACCEPTED -> PortalOrderStatus.REVIEWING
        OrderStatus.SCHEDULED -> PortalOrderStatus.SCHEDULED
        OrderStatus.FULFILLING -> PortalOrderStatus.IN_PROGRESS
        OrderStatus.FULFILLED -> PortalOrderStatus.COMPLETED
        OrderStatus.CANCELLED, OrderStatus.REJECTED -> PortalOrderStatus.CANCELLED
    }

    private data class PortalRow(val customerId: UUID, val view: PortalOrderView)
}
