package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.order.PortalOrderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Pintu pemesanan untuk PENGUNJUNG yang belum punya akun apa pun (P4.4/P4.5).
 *
 * Tenant ditentukan dari SLUG di URL, bukan dari token: pemanggilnya memang tak punya token.
 * Konsekuensinya setiap operasi di sini harus memperlakukan [tenantSlug] sebagai masukan yang
 * TAK DIPERCAYA dan menegakkan sendiri batas tenant-nya — tak ada `TenantContext` yang sudah
 * terpasang oleh filter untuk menolongnya.
 *
 * Seluruh kegagalan (slug asing, nomor pesanan asing, HP tak cocok) SENGAJA dipulangkan dengan
 * kalimat yang SAMA. Membedakannya berarti memberi penebak alat untuk memetakan slug tenant
 * mana yang ada dan nomor pesanan mana yang hidup.
 */
interface PublicOrderUseCase {

    /** Paket yang masih dijual, untuk ditampilkan di formulir pemesanan. */
    fun plans(tenantSlug: String, clientIp: String?): List<PublicPlanView>

    fun submit(tenantSlug: String, submission: PublicOrderSubmission, clientIp: String?): PublicOrderReceipt

    /**
     * [phone] adalah satu-satunya bukti kepemilikan yang dipegang pengunjung. Ia bukan rahasia
     * yang kuat — tapi dipasangkan dengan nomor pesanan yang tak bisa ditebak dan rem laju,
     * ia cukup untuk halaman yang isinya hanya "pesanan Anda sedang dijadwalkan".
     */
    fun track(tenantSlug: String, orderNumber: String, phone: String, clientIp: String?): PublicOrderTrackView
}

data class PublicPlanView(
    val planId: UUID,
    val name: String,
    val monthlyFee: BigDecimal,
    val bandwidthMbps: Int,
)

/**
 * [requestId] adalah kunci idempotensi milik klien. Pengunjung yang menekan "Kirim" dua kali
 * (atau jaringannya mengulang POST) TIDAK boleh melahirkan dua pesanan — operator akan
 * menelepon orang yang sama dua kali dan memasang dua kali.
 *
 * [honeypot] WAJIB kosong. Formulir menampilkan kolom yang tak terlihat manusia; bot pengisi
 * formulir otomatis mengisi semua kolom yang ditemukannya. Ini bukan pertahanan yang kuat, tapi
 * ia gratis dan menyaring lapisan penyalahgunaan yang paling malas sebelum sampai ke database.
 */
data class PublicOrderSubmission(
    val name: String,
    val phone: String,
    val email: String?,
    val planId: UUID,
    val address: String,
    val city: String,
    val postalCode: String,
    val latitude: Double?,
    val longitude: Double?,
    val notes: String?,
    val requestId: String?,
    val honeypot: String?,
)

/**
 * Yang dibawa pulang pengunjung. [orderNumber] + nomor HP-nya adalah kunci untuk melacak
 * kembali; tak ada id internal yang ikut, supaya UUID pesanan tak pernah beredar di luar.
 */
data class PublicOrderReceipt(
    val orderNumber: String,
    val status: PortalOrderStatus,
    val tenantName: String,
    val submittedAt: Instant,
)

/**
 * Ringkasan status yang boleh dibaca pengunjung anonim.
 *
 * SENGAJA TIDAK memuat alamat layanan, koordinat, catatan internal, alasan penolakan, nama
 * teknisi, maupun id apa pun. Siapa saja yang menebak pasangan nomor+HP dengan benar akan
 * melihat isi ini, jadi isinya harus tetap tak berbahaya bahkan bila tebakannya kebetulan tepat.
 */
data class PublicOrderTrackView(
    val orderNumber: String,
    val status: PortalOrderStatus,
    /** Kalimat untuk pelanggan bila pesanan sedang bertanda; null saat tak bertanda. */
    val statusNote: String?,
    val appointmentStartsAt: Instant?,
    val appointmentEndsAt: Instant?,
    val submittedAt: Instant,
    val updatedAt: Instant,
    val timeline: List<PublicOrderTrackEntry>,
)

data class PublicOrderTrackEntry(val status: PortalOrderStatus, val occurredAt: Instant)
