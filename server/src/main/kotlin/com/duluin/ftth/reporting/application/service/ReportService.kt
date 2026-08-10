package com.duluin.ftth.reporting.application.service

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.SubscriptionRevenue
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.helpdesk.HelpdeskReportApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.reporting.application.port.inbound.FieldOpsSummary
import com.duluin.ftth.reporting.application.port.inbound.OperationsReport
import com.duluin.ftth.reporting.application.port.inbound.ReportOverview
import com.duluin.ftth.reporting.application.port.inbound.ReportQuery
import com.duluin.ftth.reporting.application.port.inbound.RevenueSlice
import com.duluin.ftth.reporting.application.port.inbound.TechnicianPerformance
import com.duluin.ftth.workorder.WorkorderApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Merakit laporan dari kontrak publik `billing` + `customer` + `workorder` + `helpdesk` + `iam` —
 * murni baca, tak punya persistence sendiri (pola sama `subscriber360`/`gis`). Endpoint di-anchor
 * pada satu izin `reporting.report.view` di controller: laporan adalah pandangan manajerial
 * menyeluruh, jadi digating utuh, bukan per-facet.
 */
@Service
@Transactional(readOnly = true)
class ReportService(
    private val billingApi: BillingApi,
    private val customerApi: CustomerApi,
    private val workorderApi: WorkorderApi,
    private val helpdeskReportApi: HelpdeskReportApi,
    private val iamApi: IamApi,
) : ReportQuery {

    override fun overview(from: LocalDate, to: LocalDate, trailingMonths: Int): ReportOverview {
        requireOrderedRange(from, to)
        if (trailingMonths !in 1..MAX_TRAILING_MONTHS) {
            throw ValidationException("Jumlah bulan tren harus 1..$MAX_TRAILING_MONTHS")
        }

        val finance = billingApi.financialReport(from, to)
        val subscribers = customerApi.subscriberStats()

        val arpu = if (subscribers.billableCount > 0) {
            subscribers.mrr.divide(BigDecimal(subscribers.billableCount), 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        val toMonth = YearMonth.from(to)
        val fromMonth = toMonth.minusMonths((trailingMonths - 1).toLong())

        val revenue = billingApi.revenueBySubscription(from, to)
        val breakdown = breakdown(revenue)

        return ReportOverview(
            rangeStart = from,
            rangeEnd = to,
            finance = finance,
            subscribers = subscribers,
            arpu = arpu,
            monthlyRevenue = billingApi.monthlyRevenue(fromMonth, toMonth),
            // Umur piutang adalah POTRET, bukan aliran periode: selalu per hari ini, bukan per
            // ujung rentang — "berapa yang nunggak" hanya berguna sebagai keadaan sekarang.
            aging = billingApi.receivableAging(LocalDate.now()),
            churn = customerApi.churnReport(from, to),
            revenueByPackage = breakdown.byPackage,
            revenueByArea = breakdown.byArea,
        )
    }

    override fun operations(from: LocalDate, to: LocalDate): OperationsReport {
        requireOrderedRange(from, to)

        val fieldOps = workorderApi.fieldOpsReport(from, to)
        val names = iamApi.usersByIds(fieldOps.technicians.mapTo(HashSet()) { it.technicianId })
            .associate { it.id to it.name }

        return OperationsReport(
            rangeStart = from,
            rangeEnd = to,
            fieldOps = FieldOpsSummary(
                completedCount = fieldOps.completedCount,
                completedByType = fieldOps.completedByType,
                avgResolutionHours = fieldOps.avgResolutionHours,
                avgRepairResolutionHours = fieldOps.avgRepairResolutionHours,
                avgResponseHours = fieldOps.avgResponseHours,
                technicians = fieldOps.technicians.map { t ->
                    TechnicianPerformance(
                        technicianId = t.technicianId,
                        technicianName = names[t.technicianId] ?: UNKNOWN_TECHNICIAN,
                        completedCount = t.completedCount,
                        avgResolutionHours = t.avgResolutionHours,
                    )
                },
            ),
            support = helpdeskReportApi.supportReport(from, to),
        )
    }

    /**
     * Menjahit uang (`billing`, hanya kenal `subscriptionId`) dengan dimensinya (`customer` untuk
     * paket + id wilayah, `iam` untuk nama wilayah). Inilah alasan modul `reporting` ada: tak ada
     * satu modul pun yang boleh tahu ketiganya sekaligus.
     */
    private fun breakdown(revenue: List<SubscriptionRevenue>): RevenueBreakdown {
        if (revenue.isEmpty()) return RevenueBreakdown(emptyList(), emptyList())

        val dimensions = customerApi.subscriptionDimensions(revenue.mapTo(HashSet()) { it.subscriptionId })
            .associateBy { it.subscriptionId }
        val areaNames = iamApi.areasByIds(dimensions.values.mapNotNullTo(HashSet()) { it.areaId })
            .associate { it.id to it.name }

        // Langganan yang dimensinya tak terbaca (mis. sudah dihapus) tetap ikut terhitung dengan
        // label penampung, supaya jumlah keratan selalu sama dengan total pendapatan periode.
        val byPackage = revenue.sliceBy { dimensions[it.subscriptionId]?.packageName ?: UNKNOWN_PACKAGE }
        val byArea = revenue.sliceBy { row ->
            dimensions[row.subscriptionId]?.areaId?.let { areaNames[it] } ?: NO_AREA
        }
        return RevenueBreakdown(byPackage, byArea)
    }

    private fun List<SubscriptionRevenue>.sliceBy(label: (SubscriptionRevenue) -> String): List<RevenueSlice> =
        groupBy(label)
            .map { (key, rows) ->
                RevenueSlice(
                    label = key,
                    amount = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount },
                    paidInvoiceCount = rows.sumOf { it.paidInvoiceCount },
                    subscriptionCount = rows.size,
                )
            }
            .sortedWith(compareByDescending<RevenueSlice> { it.amount }.thenBy { it.label })

    private fun requireOrderedRange(from: LocalDate, to: LocalDate) {
        if (from.isAfter(to)) throw ValidationException("Tanggal mulai tak boleh setelah tanggal akhir")
    }

    private data class RevenueBreakdown(
        val byPackage: List<RevenueSlice>,
        val byArea: List<RevenueSlice>,
    )

    private companion object {
        const val MAX_TRAILING_MONTHS = 24
        const val UNKNOWN_PACKAGE = "Tanpa paket"
        const val NO_AREA = "Tanpa wilayah"
        const val UNKNOWN_TECHNICIAN = "(tidak dikenal)"
    }
}
