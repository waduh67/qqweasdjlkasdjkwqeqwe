package com.duluin.ftth.billing

import com.duluin.ftth.common.storage.StoredObject
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
     * Satu tagihan untuk HALAMAN BAYAR PUBLIK — TANPA penyaringan pemilik: kapabilitasnya adalah
     * UUID tagihan itu sendiri (bandingkan [payCustomerInvoice] yang dibatasi customerId). Isolasi
     * tenant tetap ditegakkan RLS: pemanggil wajib sudah memasang `TenantContext`. `null` bila
     * tagihannya tak ada di tenant aktif.
     */
    fun findInvoiceForPublicLink(invoiceId: UUID): PublicInvoiceRef?

    /**
     * Buat charge in-app (VA/QRIS) untuk tagihan dari halaman bayar publik. Sama seperti
     * [payCustomerInvoice] minus pembatasan pemilik. Instruksi bayar yang MASIH HIDUP dan cocok
     * (metode + channel sama, belum kedaluwarsa) dipakai ulang alih-alih memanggil gateway lagi —
     * tautan publik dipegang siapa saja, jadi tiap muat ulang tak boleh membuat sesi bayar baru.
     */
    fun payInvoiceForPublicLink(invoiceId: UUID, method: String, channel: String?): PublicInvoiceRef

    /** Gambar QRIS statis pembayaran manual tenant aktif (byte + tipe konten); null bila tak dipasang. */
    fun manualQrisImage(): StoredObject?

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
 * - [refundedAmount]/[refundCount]: pengembalian dana yang BENAR-BENAR selesai dalam rentang
 *   (by `completedAt`) — refund yang masih berjalan belum menjadi uang keluar, jadi tak dihitung.
 * - [netRevenue]: [revenueCollected] − [refundedAmount], uang yang benar-benar tinggal. Keduanya
 *   dipaparkan terpisah, tidak dilebur, karena "menerima 10 juta lalu memulangkan 2" bukan cerita
 *   yang sama dengan "menerima 8 juta" — yang pertama menandakan ada yang salah di operasional.
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
    val refundedAmount: BigDecimal = BigDecimal.ZERO,
    val refundCount: Int = 0,
    val netRevenue: BigDecimal = revenueCollected,
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

/**
 * Proyeksi satu tagihan untuk HALAMAN BAYAR PUBLIK — subset paling sempit yang cukup untuk
 * membayar. Sengaja TIDAK memuat `gatewayRef`/id sesi bayar/penanda simulasi: pemegang tautan
 * belum tentu pemilik tagihan, dan itu alat uji sandbox, bukan konsumsi publik.
 *
 * [payableOnline] = tagihan masih terbuka DAN gateway aktif tenant bukan MANUAL, jadi panel
 * VA/QRIS layak dirender. Bila false karena MANUAL, [manual] berisi instruksi transfer/QRIS
 * statis tenant sebagai gantinya. Semua nilai uang pada skala 2.
 */
data class PublicInvoiceRef(
    val id: UUID,
    val number: String,
    val customerName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amount: BigDecimal,
    /** Nama [com.duluin.ftth.billing.domain.model.InvoiceStatus], mis. "ISSUED". */
    val status: String,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    val payableOnline: Boolean,
    /** Instrumen bayar in-app terpilih (VIRTUAL_ACCOUNT/QR) & instruksinya; null bila belum pilih. */
    val payMethod: String? = null,
    val vaChannel: String? = null,
    val vaNumber: String? = null,
    val vaName: String? = null,
    val vaExpiresAt: Instant? = null,
    /** String QRIS mentah (dirender jadi kode QR di klien). */
    val qrContent: String? = null,
    val qrExpiresAt: Instant? = null,
    val manual: ManualInstructionsRef? = null,
)

/**
 * Instruksi bayar manual tenant (gateway MANUAL) untuk halaman publik — non-rahasia. Gambar QRIS
 * diambil terpisah lewat [BillingApi.manualQrisImage]; di sini hanya penandanya.
 */
data class ManualInstructionsRef(
    val transferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val qrisEnabled: Boolean,
    val qrisImageAvailable: Boolean,
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

/**
 * Satu titik tren: pendapatan tertagih pada satu bulan kalender ([month] = "YYYY-MM").
 * [revenue] bruto (uang masuk bulan itu), [refunded] pengembalian yang selesai bulan itu —
 * keduanya bisa jatuh di bulan berbeda, jadi selisihnya sengaja tak dilebur jadi satu angka.
 */
data class MonthlyRevenuePoint(
    val month: String,
    val revenue: BigDecimal,
    val paidInvoiceCount: Int,
    val refunded: BigDecimal = BigDecimal.ZERO,
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
