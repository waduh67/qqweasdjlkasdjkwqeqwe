package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import com.duluin.ftth.order.domain.model.OrderPortalFlagSource
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
    /**
     * Penanda portal ikut di baris antrean, bukan digali per pesanan.
     *
     * "Mana pesanan yang sedang menunggu pelanggan?" adalah pertanyaan paling sering ditanyakan
     * operator, dan sebelum ini satu-satunya jawabannya adalah membuka riwayat SETIAP baris satu
     * per satu — 20 permintaan untuk satu halaman. Kolomnya sudah ada di `order_record` dan sudah
     * ikut terbaca query ini; tidak membawanya naik hanya memindahkan pekerjaan ke klien.
     */
    val portalFlag: OrderPortalFlag?,
    val portalFlagReason: String?,
    val portalFlagSource: OrderPortalFlagSource?,
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
    /**
     * `true` = hanya yang BERTANDA (apa pun tandanya), `false` = hanya yang bersih, `null` = semua.
     *
     * Terpisah dari [portalFlag] karena menjawab pertanyaan yang berbeda: "apa yang tertahan?"
     * bukan "mana yang menunggu pelanggan?". Menggabungkannya jadi satu parameter berarti operator
     * yang cuma mau melihat seluruh tumpukan tertahan harus tahu lebih dulu ada tanda apa saja.
     */
    val flagged: Boolean? = null,
    /** Penyaring tanda TERTENTU. Menyaring per halaman di klien tidak cukup — barisnya tersebar. */
    val portalFlag: OrderPortalFlag? = null,
)

/**
 * Satu pesanan yang dicari lewat NOMOR-nya, untuk halaman lacak publik.
 *
 * SENGAJA bukan agregat: pemanggilnya anonim dan hanya boleh menerima status ringkas. Kalau ia
 * memuat agregat penuh, alamat layanan, koordinat, alasan penolakan internal, dan id pemesan ikut
 * terbawa ke lapisan yang pengunjungnya tak pernah kita kenali — satu kelalaian pemetaan di
 * controller sudah cukup untuk membocorkannya.
 *
 * [leadPhone]/[customerId] ada HANYA untuk memverifikasi penelepon, tak pernah dikembalikan.
 */
data class OrderTrackRow(
    val id: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val portalFlag: String?,
    val portalFlagReason: String?,
    val customerId: UUID?,
    val leadPhone: String?,
    val appointmentStartsAt: Instant?,
    val appointmentEndsAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface OrderSearchPort {
    fun search(tenantId: UUID, filter: OrderSearchFilter, pageRequest: PageRequest): Page<OrderListRow>

    /**
     * Pencocokan nomor pesanan PERSIS dalam satu tenant. [tenantId] wajib ikut di WHERE meski RLS
     * sudah menyaring: nomor `ORD-YYMM-NNNN` hanya unik per tenant, jadi tanpa itu satu kelalaian
     * memasang konteks tenant berubah menjadi pengunjung yang melacak pesanan tenant lain.
     */
    fun findByNumber(tenantId: UUID, orderNumber: String): OrderTrackRow?
}
