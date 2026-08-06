package com.duluin.ftth.reporting.application.service

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.reporting.application.port.inbound.ReportOverview
import com.duluin.ftth.reporting.application.port.inbound.ReportQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/**
 * Merakit laporan dari kontrak publik `billing` + `customer` — murni baca, tak punya
 * persistence sendiri (pola sama `subscriber360`/`gis`). Endpoint di-anchor pada satu izin
 * `reporting.report.view` di controller: laporan adalah pandangan manajerial menyeluruh, jadi digating
 * utuh, bukan per-facet.
 */
@Service
@Transactional(readOnly = true)
class ReportService(
    private val billingApi: BillingApi,
    private val customerApi: CustomerApi,
) : ReportQuery {

    override fun overview(from: java.time.LocalDate, to: java.time.LocalDate, trailingMonths: Int): ReportOverview {
        if (from.isAfter(to)) throw ValidationException("Tanggal mulai tak boleh setelah tanggal akhir")
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

        return ReportOverview(
            rangeStart = from,
            rangeEnd = to,
            finance = finance,
            subscribers = subscribers,
            arpu = arpu,
            monthlyRevenue = billingApi.monthlyRevenue(fromMonth, toMonth),
        )
    }

    private companion object {
        const val MAX_TRAILING_MONTHS = 24
    }
}
