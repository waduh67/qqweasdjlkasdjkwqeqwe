package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.PortalOrderView
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.portal.PortalCustomerSession
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/portal/orders")
class PortalOrderController(
    private val orders: OrderApi,
    private val currentCustomer: PortalCustomerSession,
) {
    @GetMapping
    fun list(): List<PortalOrderView> = orders.portalOrders(currentCustomer.currentCustomerId())

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): PortalOrderView = orders.portalOrder(currentCustomer.currentCustomerId(), id)
        ?: throw NotFoundException("Order tidak ditemukan")
}
