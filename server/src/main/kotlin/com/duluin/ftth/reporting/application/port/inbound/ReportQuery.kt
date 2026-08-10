package com.duluin.ftth.reporting.application.port.inbound

import com.duluin.ftth.billing.BillingFinancialReport
import com.duluin.ftth.billing.MonthlyRevenuePoint
import com.duluin.ftth.billing.ReceivableAging
import com.duluin.ftth.customer.ChurnReport
import com.duluin.ftth.customer.SubscriberStats
import com.duluin.ftth.helpdesk.HelpdeskSupportReport
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Laporan & analitik satu tenant — satu tempat data keuangan (modul `billing`), pelanggan/
 * langganan (`customer`), kerja lapangan (`workorder`), dan meja bantuan (`helpdesk`)
 * dipertemukan menjadi angka yang dibaca pemilik ISP.
 *
 * Seperti `subscriber360`/`gis`, modul `reporting` sengaja tak punya tabel sendiri: ia menyusun
 * jawaban dari kontrak publik modul lain, sehingga pertanyaan "bagaimana bisnisku periode ini"
 * bisa dijawab satu panggilan tanpa membuat modul-modul itu saling bergantung. Billing tetap
 * satu-satunya yang menghitung uang; customer satu-satunya yang menghitung langganan —
 * reporting hanya merangkai + menurunkan metrik lintas-domain (ARPU, pendapatan per paket/area).
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

    /**
     * Rakit laporan OPERASIONAL untuk rentang [from]..[to] (inklusif): kerja lapangan (MTTR &
     * produktivitas teknisi) dan meja bantuan (respons, penyelesaian, kepatuhan SLA).
     *
     * Dipisah dari [overview] karena sumbernya beda modul dan pertanyaannya beda pula — pemilik
     * membaca uang, penyelia membaca kecepatan kerja. Memisahkannya juga menjaga halaman laporan
     * tetap ringan: yang tak dibuka tak dihitung.
     *
     * @throws com.duluin.ftth.common.domain.error.ValidationException bila [from] setelah [to].
     */
    fun operations(from: LocalDate, to: LocalDate): OperationsReport
}

/**
 * Ringkasan bisnis tenant pada saat dibaca. [finance] = angka uang dalam rentang (billing);
 * [subscribers] = potret pelanggan/langganan (customer); [arpu] = pendapatan rata-rata per
 * langganan billable (MRR ÷ jumlah langganan ACTIVE+ISOLATED, skala 2; nol bila tak ada);
 * [monthlyRevenue] = tren pendapatan tertagih per bulan untuk digambar; [aging] = umur piutang
 * saat ini; [churn] = perputaran langganan di rentang; [revenueByPackage]/[revenueByArea] =
 * pendapatan tertagih dibedah menurut dimensi milik `customer`/`iam`.
 */
data class ReportOverview(
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val finance: BillingFinancialReport,
    val subscribers: SubscriberStats,
    val arpu: BigDecimal,
    val monthlyRevenue: List<MonthlyRevenuePoint>,
    val aging: ReceivableAging,
    val churn: ChurnReport,
    val revenueByPackage: List<RevenueSlice>,
    val revenueByArea: List<RevenueSlice>,
)

/**
 * Sekerat pendapatan menurut satu dimensi (paket atau wilayah), terbesar dulu. [label] sudah siap
 * tampil — langganan yang pemiliknya tak punya wilayah dikelompokkan sebagai "Tanpa wilayah",
 * bukan dibuang, supaya jumlah keratannya tetap sama dengan total pendapatan.
 */
data class RevenueSlice(
    val label: String,
    val amount: BigDecimal,
    val paidInvoiceCount: Int,
    val subscriptionCount: Int,
)

/**
 * Laporan operasional tenant pada satu rentang: [fieldOps] dari modul `workorder` (diperkaya nama
 * teknisi lewat `iam`), [support] apa adanya dari modul `helpdesk`.
 */
data class OperationsReport(
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val fieldOps: FieldOpsSummary,
    val support: HelpdeskSupportReport,
)

/**
 * Kerja lapangan pada satu rentang. Cermin `WorkorderApi.FieldOpsReport` dengan satu perbedaan:
 * teknisi sudah bernama. Modul `workorder` tak tahu nama pengguna (itu milik `iam`), jadi
 * penjahitannya terjadi di sini — bukan dengan membuat workorder bergantung pada iam.
 */
data class FieldOpsSummary(
    val completedCount: Int,
    val completedByType: Map<String, Int>,
    val avgResolutionHours: Double?,
    val avgRepairResolutionHours: Double?,
    val avgResponseHours: Double?,
    val technicians: List<TechnicianPerformance>,
)

/** Produktivitas satu teknisi; [technicianName] jadi "(tidak dikenal)" bila akunnya sudah hilang. */
data class TechnicianPerformance(
    val technicianId: UUID,
    val technicianName: String,
    val completedCount: Int,
    val avgResolutionHours: Double?,
)
