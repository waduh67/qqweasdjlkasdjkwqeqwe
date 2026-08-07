package com.duluin.ftth.reporting

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.BillingFinancialReport
import com.duluin.ftth.billing.MonthlyRevenuePoint
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.SubscriberStats
import com.duluin.ftth.reporting.application.service.ReportService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Menguji perakitan laporan: ARPU = MRR ÷ langganan billable (pembulatan & kasus nol),
 * jendela bulan tren dihitung mundur dari tanggal akhir, dan validasi rentang/trailing.
 * Fake murni — reporting tak menyentuh DB, hanya merangkai kontrak billing + customer.
 */
class ReportServiceTest {

    private val finance = BillingFinancialReport(
        revenueCollected = BigDecimal("500000"),
        paidInvoiceCount = 5,
        issuedAmount = BigDecimal("700000"),
        issuedInvoiceCount = 7,
        outstandingAmount = BigDecimal("200000"),
        outstandingInvoiceCount = 2,
        statusCounts = mapOf("PAID" to 5, "ISSUED" to 2),
    )

    @Test
    fun `overview menghitung ARPU dan jendela tren mundur dari tanggal akhir`() {
        val billing = FakeBillingApi(finance)
        val service = ReportService(
            billing,
            FakeCustomerApi(SubscriberStats(totalCustomers = 10, subscriptionsByStatus = mapOf("ACTIVE" to 3), billableCount = 3, mrr = BigDecimal("300000"))),
        )

        val view = service.overview(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), trailingMonths = 6)

        assertThat(view.arpu).isEqualByComparingTo("100000") // 300000 / 3
        assertThat(view.finance).isSameAs(finance)
        assertThat(view.subscribers.totalCustomers).isEqualTo(10)
        // toMonth = Agu 2026; fromMonth = 6 bulan mundur inklusif = Mar 2026.
        assertThat(billing.askedFrom).isEqualTo(YearMonth.of(2026, 3))
        assertThat(billing.askedTo).isEqualTo(YearMonth.of(2026, 8))
    }

    @Test
    fun `ARPU nol saat tak ada langganan billable`() {
        val service = ReportService(
            FakeBillingApi(finance),
            FakeCustomerApi(SubscriberStats(totalCustomers = 0, subscriptionsByStatus = emptyMap(), billableCount = 0, mrr = BigDecimal.ZERO)),
        )

        val view = service.overview(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), trailingMonths = 6)

        assertThat(view.arpu).isEqualByComparingTo("0")
    }

    @Test
    fun `menolak rentang terbalik`() {
        val service = ReportService(FakeBillingApi(finance), FakeCustomerApi(zeroStats()))

        assertThatThrownBy { service.overview(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1), 6) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `menolak trailingMonths di luar rentang`() {
        val service = ReportService(FakeBillingApi(finance), FakeCustomerApi(zeroStats()))
        val from = LocalDate.of(2026, 8, 1)
        val to = LocalDate.of(2026, 8, 15)

        assertThatThrownBy { service.overview(from, to, 0) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.overview(from, to, 25) }.isInstanceOf(ValidationException::class.java)
    }

    private fun zeroStats() = SubscriberStats(0, emptyMap(), 0, BigDecimal.ZERO)

    private class FakeBillingApi(private val report: BillingFinancialReport) : BillingApi {
        var askedFrom: YearMonth? = null
        var askedTo: YearMonth? = null

        override fun financialReport(from: LocalDate, to: LocalDate) = report

        override fun monthlyRevenue(fromMonth: YearMonth, toMonth: YearMonth): List<MonthlyRevenuePoint> {
            askedFrom = fromMonth
            askedTo = toMonth
            return listOf(MonthlyRevenuePoint(fromMonth.toString(), BigDecimal.ZERO, 0))
        }

        override fun findAccountSummary(customerId: UUID) = throw UnsupportedOperationException()
        override fun findCustomerInvoices(customerId: UUID) = throw UnsupportedOperationException()
        override fun findCustomerPayments(customerId: UUID) = throw UnsupportedOperationException()
        override fun paymentMethods() = throw UnsupportedOperationException()
        override fun payCustomerInvoice(customerId: UUID, invoiceId: UUID, method: String, channel: String?) =
            throw UnsupportedOperationException()
    }

    private class FakeCustomerApi(private val stats: SubscriberStats) : CustomerApi {
        override fun subscriberStats() = stats

        override fun findCustomer(id: UUID) = throw UnsupportedOperationException()
        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findSubscriptionsByCustomer(customerId: UUID) = throw UnsupportedOperationException()
        override fun findOccupantsOfOdp(odpId: UUID) = throw UnsupportedOperationException()
        override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
        override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
        override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findOnusBySerialNumbers(serialNumbers: Set<String>) = throw UnsupportedOperationException()
        override fun placementsForOnus(onuIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
        override fun provisionOnu(command: com.duluin.ftth.customer.ProvisionOnuCommand) = throw UnsupportedOperationException()
        override fun findBillableSubscriptions() = throw UnsupportedOperationException()
        override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun registerCustomer(command: com.duluin.ftth.customer.RegisterCustomerCommand) = throw UnsupportedOperationException()
        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: BigDecimal?) = throw UnsupportedOperationException()
        override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) = throw UnsupportedOperationException()
        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: java.time.Instant?, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<java.util.UUID>): List<com.duluin.ftth.customer.CustomerExportRow> = throw UnsupportedOperationException()
    }
}
