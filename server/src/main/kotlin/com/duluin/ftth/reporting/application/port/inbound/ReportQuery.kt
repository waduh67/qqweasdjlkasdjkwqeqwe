package com.duluin.ftth.reporting.application.port.inbound

import com.duluin.ftth.billing.BillingFinancialReport
import com.duluin.ftth.billing.MonthlyRevenuePoint
import com.duluin.ftth.customer.SubscriberStats
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Laporan & analitik satu tenant — satu tempat data keuangan (modul `billing`) dan
 * pelanggan/langganan (modul `customer`) dipertemukan menjadi angka yang dibaca pemilik ISP.
 *
 * Seperti `subscriber360`/`gis`, modul `reporting` sengaja tak punya tabel sendiri: ia menyusun
 * jawaban dari `BillingApi` dan `CustomerApi` (kontrak publik), sehingga pertanyaan "bagaimana
 * bisnisku periode ini" bisa dijawab satu panggilan tanpa membuat modul-modul itu saling
 * bergantung. Billing tetap satu-satunya yang menghitung uang; customer satu-satunya yang
 * menghitung langganan — reporting hanya merangkai + menurunkan metrik lintas-domain (ARPU).
 */
interface ReportQuery {

    /**
     * Rakit ringkasan bisnis untuk rentang [from]..[to] (inklusif) plus tren pendapatan
     * [trailingMonths] bulan terakhir (dihitung mundur dari bulan [to]).
     *
     * @throws com.duluin.ftth.common.domain.error.ValidationException bila [from] setelah [to]
     *         atau [trailingMonths] di luar 1..24.
     */
    fun overview(from: LocalDate, to: LocalDate, trailingMonths: Int): ReportOverview
}

/**
 * Ringkasan bisnis tenant pada saat dibaca. [finance] = angka uang dalam rentang (billing);
 * [subscribers] = potret pelanggan/langganan (customer); [arpu] = pendapatan rata-rata per
 * langganan billable (MRR ÷ jumlah langganan ACTIVE+ISOLATED, skala 2; nol bila tak ada);
 * [monthlyRevenue] = tren pendapatan tertagih per bulan untuk digambar.
 */
data class ReportOverview(
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val finance: BillingFinancialReport,
    val subscribers: SubscriberStats,
    val arpu: BigDecimal,
    val monthlyRevenue: List<MonthlyRevenuePoint>,
)
