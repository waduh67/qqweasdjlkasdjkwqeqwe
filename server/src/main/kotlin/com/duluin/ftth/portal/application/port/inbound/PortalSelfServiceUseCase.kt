package com.duluin.ftth.portal.application.port.inbound

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Baca-saja self-service pelanggan portal. SEMUA metode ter-scope ke SATU pelanggan
 * ([customerId]) — controller mengisinya dari principal portal yang login, tak pernah dari
 * input klien, sehingga pelanggan hanya bisa membaca datanya sendiri.
 *
 * Merangkai kontrak publik modul lain (customer, catalog, billing, bng, cpe) — persis pola
 * `ReportService`/`Subscriber360Service`. Portal tak menyentuh tabel modul lain.
 */
interface PortalSelfServiceUseCase {

    /** Profil pelanggan + langganan beserta detail paket (Profil & paket). */
    fun profile(customerId: UUID): PortalAccountView

    /** Ringkasan rekening + daftar tagihan (tautan bayar) + riwayat pembayaran. */
    fun billing(customerId: UUID): PortalBillingView

    /** Status koneksi: sesi PPPoE terkini + perangkat CPE. */
    fun connection(customerId: UUID): PortalConnectionView
}

data class PortalAccountView(
    val customerId: UUID,
    val code: String,
    val name: String,
    val phone: String?,
    val status: String,
    val subscriptions: List<PortalSubscriptionView>,
)

data class PortalSubscriptionView(
    val subscriptionId: UUID,
    val packageName: String,
    val bandwidthMbps: Int,
    val status: String,
    /** Harga paket dari katalog (indikatif); `null` bila paket telah dinonaktifkan/terhapus. */
    val monthlyFee: BigDecimal?,
    val downMbps: Int?,
    val upMbps: Int?,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
)

data class PortalBillingView(
    val outstandingAmount: BigDecimal,
    val outstandingCount: Int,
    val unpaidCount: Int,
    val oldestDueDate: LocalDate?,
    val lastPaidAt: Instant?,
    val invoices: List<PortalInvoiceView>,
    val payments: List<PortalPaymentView>,
)

data class PortalInvoiceView(
    val id: UUID,
    val number: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    val status: String,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    /** Bisa dibayar online sekarang: masih terbuka (ISSUED/OVERDUE) dan punya tautan bayar. */
    val payable: Boolean,
    /** Tautan bayar hosted gateway; hanya untuk tagihan terbuka, `null` selain itu. */
    val payUrl: String?,
)

data class PortalPaymentView(
    val id: UUID,
    val invoiceId: UUID,
    val amount: BigDecimal,
    val provider: String,
    val paidAt: Instant,
    val note: String?,
)

data class PortalConnectionView(
    val session: PortalSessionView?,
    val devices: List<PortalDeviceView>,
)

/** Sesi PPPoE terkini pelanggan — tanpa rahasia (password/secret tak pernah keluar). */
data class PortalSessionView(
    val username: String,
    val accessStatus: String,
    val planName: String?,
    val online: Boolean,
    val framedIp: String?,
    val nasName: String?,
    val uptimeSeconds: Long?,
    val startedAt: Instant?,
    val lastSeenAt: Instant?,
)

data class PortalDeviceView(
    val deviceId: UUID,
    val serialNumber: String,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val ipAddress: String?,
    val online: Boolean,
    val lastInformAt: Instant?,
)
