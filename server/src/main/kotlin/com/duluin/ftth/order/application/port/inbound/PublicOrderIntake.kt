package com.duluin.ftth.order.application.port.inbound

import java.util.UUID

/**
 * Bagian TRANSAKSIONAL dari pintu pemesanan publik, dengan tenant sudah diketahui.
 *
 * Dipisah dari [PublicOrderUseCase] bukan demi kerapian, melainkan karena URUTAN:
 * [com.duluin.ftth.common.tenant.TenantContext] harus terpasang SEBELUM session Hibernate
 * dibuka. Kalau satu bean saja memegang `@Transactional` sekaligus me-resolve slug, transaksi
 * (dan koneksinya, lengkap dengan GUC `app.tenant_id` = sentinel ROOT) sudah dimulai sebelum
 * tenant-nya diketahui — dan di bawah RLS setiap query lalu memulangkan NOL BARIS TANPA ERROR.
 * Pesanan pengunjung akan gagal dengan pesan yang sama sekali tak menunjuk sebabnya.
 *
 * Karena itu: [PublicOrderUseCase] TIDAK transaksional dan memasang tenant; implementasi
 * antarmuka ini yang transaksional dan dipanggil DARI DALAM `runAs`.
 */
interface PublicOrderIntake {

    fun plans(tenantId: UUID): List<PublicPlanView>

    fun submit(tenantId: UUID, tenantName: String, submission: PublicOrderSubmission): PublicOrderReceipt

    fun track(tenantId: UUID, orderNumber: String, phone: String): PublicOrderTrackView
}
