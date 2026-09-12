package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.order.domain.model.Order
import com.duluin.ftth.order.OrderView
import java.util.UUID

interface OrderRepository {
    fun save(order: Order)
    fun find(id: UUID): Order?
    fun findForFulfillment(id: UUID): Order? = find(id)
    /**
     * Pesanan milik seorang pelanggan, TERMASUK yang dibuatnya semasa masih calon pelanggan.
     * Lihat `OrderJpaRepository.findAllOwnedBy` — pemesan pesanan lama tak pernah dipindah dari
     * lead ke customer, jadi kepemilikan harus ditelusuri lewat lead yang sudah dipromosikan.
     */
    fun findByCustomer(customerId: UUID): List<Order>

    /** Satu pesanan milik pelanggan tertentu; `null` bila bukan miliknya (atau tak ada). */
    fun findOwnedBy(customerId: UUID, orderId: UUID): Order? =
        findByCustomer(customerId).firstOrNull { it.id == orderId }

    fun findOutcome(tenantId: UUID, namespace: String, key: String): StoredOrderOutcome? = null
    fun saveOutcome(tenantId: UUID, namespace: String, key: String, hash: String, value: OrderView) {}
}

data class StoredOrderOutcome(val hash: String, val value: OrderView)
