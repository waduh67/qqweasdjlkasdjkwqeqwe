package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.order.PortalOrderView
import com.duluin.ftth.order.application.port.outbound.OrderCustomerProjection
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.toPortalView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Riwayat pesanan pelanggan, DITURUNKAN langsung dari `order_record`.
 *
 * KENAPA tanpa tabel proyeksi sendiri: pandangan portal tidak memuat satu pun data yang belum
 * ada di `order_record` + `order_line` — ia hanya membuang koordinat dan memetakan status ke
 * kosakata yang aman dilihat pelanggan. Tabel salinan berarti satu sumber kebenaran kedua yang
 * harus dijaga sinkron, dan proyeksi yang ketinggalan (event hilang, urutan terbalik, migrasi
 * lupa membangun ulang) muncul sebagai pelanggan melihat status pesanan yang SALAH — kegagalan
 * yang jauh lebih buruk daripada biaya join yang dihemat. Query-nya sudah ditopang index
 * `ix_order_record_tenant_customer`.
 *
 * Pendahulunya adalah `ConcurrentHashMap` di dalam proses, jadi setiap restart aplikasi
 * mengosongkan riwayat pesanan SELURUH pelanggan. Turunan ini tak punya state sama sekali,
 * sehingga masalah itu hilang menurut konstruksi, bukan menurut disiplin.
 */
@Component
class OrderCustomerProjectionAdapter(
    private val orders: OrderRepository,
) : OrderCustomerProjection {

    @Transactional(readOnly = true)
    override fun findByCustomer(tenantId: UUID, customerId: UUID): List<PortalOrderView> =
        orders.findByCustomer(customerId)
            .filter { it.tenantId == tenantId }
            .mapNotNull { it.toPortalView() }

    /**
     * Diambil lewat id pesanan, lalu kepemilikannya diperiksa — bukan menyaring seluruh riwayat
     * pelanggan di memori. Pesanan milik orang lain jatuh ke `null` sehingga pemanggilnya
     * menjawab 404: keberadaannya pun tak boleh bocor.
     */
    @Transactional(readOnly = true)
    override fun find(tenantId: UUID, customerId: UUID, orderId: UUID): PortalOrderView? =
        orders.findOwnedBy(customerId, orderId)
            ?.takeIf { it.tenantId == tenantId }
            ?.toPortalView()
}
