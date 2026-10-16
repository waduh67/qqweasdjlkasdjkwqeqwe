package com.karuhun.ftth.portal.adapter.inbound.web

import com.karuhun.ftth.order.OrderApi
import com.karuhun.ftth.order.PortalOrderView
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.portal.PortalCustomerSession
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
