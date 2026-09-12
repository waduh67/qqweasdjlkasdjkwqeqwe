package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.domain.model.OrderStatus
import java.time.Instant
import java.util.UUID

/**
 * Baris antrean pesanan untuk back-office. SENGAJA bukan agregat [com.duluin.ftth.order.domain.model.Order]:
 * daftar butuh data pemesan yang sudah didenormalisasi (nama & HP calon pelanggan ikut di-join),
 * dan memuat agregat penuh berikut line-nya untuk 20 baris sekaligus berarti N+1 query.
 */
data class OrderListRow(
    val id: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val customerId: UUID?,
    val leadId: UUID?,
    val leadName: String?,
    val leadPhone: String?,
    val address: String,
    val city: String,
    val appointmentStartsAt: Instant?,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * [query] dicocokkan ke nomor pesanan, nama & HP calon pelanggan, dan alamat layanan.
 *
 * CATATAN: nama pelanggan TERDAFTAR tidak ikut dicari. Tabelnya milik module `customer` dan
 * module `order` tak boleh menyentuhnya langsung; nama pelanggan hanya diresolusi untuk
 * ditampilkan lewat `CustomerApi`. Untuk pesanan dari pelanggan lama, cari lewat nomor
 * pesanannya.
 */
data class OrderSearchFilter(
    val query: String? = null,
    val status: OrderStatus? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
)

interface OrderSearchPort {
    fun search(tenantId: UUID, filter: OrderSearchFilter, pageRequest: PageRequest): Page<OrderListRow>
}
