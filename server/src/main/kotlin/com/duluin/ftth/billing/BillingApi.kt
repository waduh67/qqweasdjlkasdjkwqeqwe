package com.duluin.ftth.billing

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Kontrak publik modul `billing` untuk modul lain (mis. aggregator Subscriber-360).
 * Menyediakan RINGKASAN rekening satu pelanggan — saldo tunggakan dihitung di SERVER
 * (dulu di browser: `web/.../billing.ts` + TagihanTab), jadi angka uang punya satu
 * sumber kebenaran. Billing "sink": tak pernah memanggil balik pemanggilnya.
 */
interface BillingApi {

    /**
     * Ringkasan rekening pelanggan: tunggakan, jumlah tagihan belum lunas, jatuh tempo
     * terlama, dan pembayaran terakhir. Selalu mengembalikan objek (nol tagihan =
     * ringkasan bernilai nol), bukan null.
     */
    fun findAccountSummary(customerId: UUID): BillingAccountSummary

    /**
     * Tagihan seorang pelanggan (semua status), terbit terbaru dulu — untuk portal
     * self-service. Membawa [CustomerInvoiceRef.payUrl] agar portal bisa menautkan "Bayar
     * online" tanpa menembus batas modul billing.
     */
    fun findCustomerInvoices(customerId: UUID): List<CustomerInvoiceRef>

    /** Riwayat pembayaran seorang pelanggan, terbaru dulu — untuk portal self-service. */
    fun findCustomerPayments(customerId: UUID): List<CustomerPaymentRef>

    /** Metode bayar in-app yang tersedia (QRIS + Virtual Account) — untuk portal self-service. */
    fun paymentMethods(): List<PaymentMethodOption>

    /**
     * Buat charge in-app (VA/QRIS) untuk satu tagihan milik [customerId] dengan instrumen [method]
     * (`VIRTUAL_ACCOUNT`/`QR`) + [channel] bank (wajib untuk VA), lalu kembalikan tagihan berisi
     * instruksi bayar. Untuk portal self-service: pelanggan hanya bisa membayar tagihannya sendiri
     * (dibatasi [customerId]). NotFound bila tagihan bukan miliknya; Validation bila tak dapat dibayar.
     */
    fun payCustomerInvoice(customerId: UUID, invoiceId: UUID, method: String, channel: String?): CustomerInvoiceRef

    /**
     * Laporan keuangan TENANT untuk rentang [from]..[to] (inklusif) — dipakai modul
     * `reporting` menyusun laporan lintas-domain. Billing tetap satu-satunya yang
     * menyentuh tabel tagihan/pembayaran (RLS per tenant aktif), jadi angka uang punya
     * satu sumber kebenaran.
     */
    fun financialReport(from: LocalDate, to: LocalDate): BillingFinancialReport

    /**
     * Tren pendapatan tertagih per bulan kalender (by `paidAt`) untuk rentang bulan
     * [fromMonth]..[toMonth] inklusif. Bulan tanpa pembayaran tetap muncul bernilai nol,
     * jadi deret siap digambar tanpa pemanggil menambal bolong.
     */
    fun monthlyRevenue(fromMonth: YearMonth, toMonth: YearMonth): List<MonthlyRevenuePoint>
}

/**
 * Ringkasan keuangan satu tenant pada satu rentang. Semua nilai uang pada skala 2.
 *
 * - [revenueCollected]/[paidInvoiceCount]: tagihan LUNAS yang `paidAt`-nya jatuh dalam
 *   rentang — uang yang benar-benar masuk pada periode itu.
 * - [issuedAmount]/[issuedInvoiceCount]: tagihan yang TERBIT dalam rentang (by `issuedAt`) —
 *   yang ditagihkan, lunas atau belum.
 * - [outstandingAmount]/[outstandingInvoiceCount]: tunggakan SNAPSHOT saat laporan dibuat
 *   (OVERDUE, atau ISSUED yang sudah lewat jatuh tempo) — sengaja tak dibatasi rentang,
 *   karena "yang belum dibayar sekarang" adalah potret, bukan aliran periode.
 * - [statusCounts]: jumlah SELURUH tagihan tenant per nama status (potret distribusi).
 */
data class BillingFinancialReport(
    val revenueCollected: BigDecimal,
    val paidInvoiceCount: Int,
    val issuedAmount: BigDecimal,
    val issuedInvoiceCount: Int,
    val outstandingAmount: BigDecimal,
    val outstandingInvoiceCount: Int,
    val statusCounts: Map<String, Int>,
)

/**
 * Proyeksi satu tagihan untuk pelanggan (portal self-service). Sengaja tak membocorkan
 * [gatewayRef] internal; [payUrl] disertakan agar portal bisa menautkan pembayaran online
 * (null = gateway manual / belum ada tautan bayar). Semua nilai uang pada skala 2.
 */
data class CustomerInvoiceRef(
    val id: UUID,
    val number: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    /** Nama [com.duluin.ftth.billing.domain.model.InvoiceStatus], mis. "ISSUED". */
    val status: String,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    val gatewayProvider: String?,
    val payUrl: String?,
    /** Instrumen bayar in-app terpilih (VIRTUAL_ACCOUNT/QR) & instruksinya; null bila belum pilih. */
    val payMethod: String? = null,
    val vaChannel: String? = null,
    val vaNumber: String? = null,
    val vaName: String? = null,
    val vaExpiresAt: Instant? = null,
    /** String QRIS mentah (dirender jadi kode QR di klien). */
    val qrContent: String? = null,
    val qrUrl: String? = null,
    val qrExpiresAt: Instant? = null,
)

/** Proyeksi satu pembayaran untuk pelanggan (portal self-service). */
data class CustomerPaymentRef(
    val id: UUID,
    val invoiceId: UUID,
    val amount: BigDecimal,
    val provider: String,
    val paidAt: Instant,
    val note: String?,
)

/** Satu titik tren: pendapatan tertagih pada satu bulan kalender ([month] = "YYYY-MM"). */
data class MonthlyRevenuePoint(
    val month: String,
    val revenue: BigDecimal,
    val paidInvoiceCount: Int,
)

/**
 * Ringkasan rekening satu pelanggan pada saat dibaca. [outstandingAmount] = Σ nilai
 * tagihan MENUNGGAK (berstatus OVERDUE atau ISSUED yang sudah lewat jatuh tempo);
 * PAID/VOID dikecualikan. [outstandingCount] jumlah tagihan menunggak itu;
 * [unpaidCount] jumlah tagihan belum lunas apa pun (ISSUED+OVERDUE, termasuk yang belum
 * jatuh tempo). [oldestDueDate] = jatuh tempo paling lama di antara yang menunggak
 * (indikator berapa lama nunggak). [lastPaidAt] = pembayaran terakhir (paidAt terbaru
 * lintas tagihan).
 */
data class BillingAccountSummary(
    val customerId: UUID,
    val outstandingAmount: BigDecimal,
    val outstandingCount: Int,
    val unpaidCount: Int,
    val oldestDueDate: LocalDate?,
    val lastPaidAt: Instant?,
)
