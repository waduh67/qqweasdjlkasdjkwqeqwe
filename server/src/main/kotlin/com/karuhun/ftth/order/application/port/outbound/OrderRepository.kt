package com.karuhun.ftth.order.application.port.outbound

import com.karuhun.ftth.order.domain.model.Order
import com.karuhun.ftth.order.OrderView
import java.util.UUID

interface OrderRepository {
    fun save(order: Order)
    fun find(id: UUID): Order?
    fun findForFulfillment(id: UUID): Order? = find(id)
    fun findByCustomer(customerId: UUID): List<Order>
    fun findOutcome(tenantId: UUID, namespace: String, key: String): StoredOrderOutcome? = null
    fun saveOutcome(tenantId: UUID, namespace: String, key: String, hash: String, value: OrderView) {}
}

data class StoredOrderOutcome(val hash: String, val value: OrderView)
