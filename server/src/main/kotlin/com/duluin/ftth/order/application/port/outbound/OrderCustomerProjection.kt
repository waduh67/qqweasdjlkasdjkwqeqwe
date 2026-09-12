package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.order.PortalOrderView
import java.util.UUID

/**
 * Riwayat pesanan seperti yang dilihat PELANGGAN di portal.
 *
 * Sebelumnya ini `ConcurrentHashMap` di dalam proses, jadi riwayat pesanan pelanggan KOSONG
 * setiap kali aplikasi restart — pelanggan yang memesan kemarin membuka portal hari ini dan
 * melihat halaman kosong. Kontrak ini sekarang dijamin durabel.
 */
interface OrderCustomerProjection {
    fun findByCustomer(tenantId: UUID, customerId: UUID): List<PortalOrderView>
    fun find(tenantId: UUID, customerId: UUID, orderId: UUID): PortalOrderView?
}
