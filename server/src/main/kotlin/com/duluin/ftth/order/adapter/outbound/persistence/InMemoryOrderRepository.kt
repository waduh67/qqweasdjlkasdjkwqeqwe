package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.application.port.outbound.StoredOrderOutcome
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.domain.model.Order
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryOrderRepository : OrderRepository {
    private val orders = ConcurrentHashMap<UUID, Order>()
    private val outcomes = ConcurrentHashMap<Triple<UUID, String, String>, StoredOrderOutcome>()
    override fun save(order: Order) { orders[order.id] = order }
    override fun find(id: UUID): Order? = orders[id]
    override fun findByCustomer(customerId: UUID): List<Order> = orders.values.filter { it.customerId == customerId }.sortedBy { it.id }
    override fun findOutcome(tenantId: UUID, namespace: String, key: String) = outcomes[Triple(tenantId, namespace, key)]
    override fun saveOutcome(tenantId: UUID, namespace: String, key: String, hash: String, value: OrderView) {
        outcomes[Triple(tenantId, namespace, key)] = StoredOrderOutcome(hash, value)
    }
}
