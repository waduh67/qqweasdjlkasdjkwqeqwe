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
