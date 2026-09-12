package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.order.application.port.inbound.OrderQuery
import com.duluin.ftth.order.application.port.inbound.OrderSummaryView
import com.duluin.ftth.order.application.port.inbound.OrderTimelineEntryView
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.application.port.outbound.OrderSearchFilter
import com.duluin.ftth.order.application.port.outbound.OrderSearchPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrderQueryService(
    private val search: OrderSearchPort,
    private val audit: OrderAuditStore,
    private val orders: OrderRepository,
    private val currentUser: CurrentUserProvider,
    private val customers: CustomerApi,
) : OrderQuery {

    @Transactional(readOnly = true)
    override fun search(filter: OrderSearchFilter, pageRequest: PageRequest): Page<OrderSummaryView> {
        val tenantId = currentUser.current().tenantId
        val page = search.search(tenantId, filter, pageRequest)
        // Nama pelanggan diresolusi SEKALI untuk seluruh halaman; per-baris berarti N+1
        // panggilan lintas module untuk setiap kali antrean dibuka.
        val byId = customers.findCustomersByIds(page.content.mapNotNullTo(mutableSetOf()) { it.customerId })
            .associateBy { it.id }
        return page.map { row ->
            val customer = row.customerId?.let(byId::get)
            OrderSummaryView.from(
                row,
                requesterName = row.leadName ?: customer?.name,
                requesterPhone = row.leadPhone ?: customer?.phone,
            )
        }
    }

    /**
     * Riwayat pesanan. Kepemilikan tenant diperiksa lewat agregatnya dulu supaya pesanan tenant
     * lain dijawab 404 — bukan daftar kosong yang membuat operator mengira riwayatnya hilang.
     */
    @Transactional(readOnly = true)
    override fun timeline(orderId: UUID): List<OrderTimelineEntryView> {
        val tenantId = currentUser.current().tenantId
        val order = orders.find(orderId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Order tidak ditemukan")
        return audit.timeline(tenantId, order.id).map(OrderTimelineEntryView::from)
    }
}
