package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.service.BillingApiService
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Menguji perhitungan ringkasan rekening [BillingApiService] memakai fake repo murni —
 * memindahkan money-math tunggakan dari browser ke server. Fokus: hanya OVERDUE dan
 * ISSUED yang sudah lewat jatuh tempo yang dihitung menunggak; PAID/VOID dan ISSUED
 * yang belum jatuh tempo tidak; agregat count/oldestDue/lastPaid benar.
 */
class BillingApiServiceTest {

    private val customerId = UuidV7.generate()

    @Test
    fun `hanya OVERDUE dan ISSUED lewat tempo yang dihitung menunggak`() {
        val paidAt = Instant.parse("2026-07-01T03:00:00Z")
        val invoices = listOf(
            issued(amount = "50000", dueDate = plusDays(5)),                 // belum jatuh tempo → tak menunggak
            issued(amount = "100000", dueDate = minusDays(3)),               // ISSUED lewat tempo → menunggak
            overdue(amount = "75000", dueDate = minusDays(10)),              // OVERDUE → menunggak
            paid(amount = "200000", dueDate = minusDays(20), paidAt = paidAt), // lunas → tak menunggak
            voided(amount = "30000", dueDate = minusDays(30)),               // dibatalkan → tak menunggak
        )
        val service = billing(FakeInvoiceRepository(invoices))

        val summary = service.findAccountSummary(customerId)

        assertThat(summary.customerId).isEqualTo(customerId)
        assertThat(summary.outstandingAmount).isEqualByComparingTo("175000") // 100000 + 75000
        assertThat(summary.outstandingCount).isEqualTo(2)
        assertThat(summary.unpaidCount).isEqualTo(3) // 2 ISSUED + 1 OVERDUE (PAID/VOID tak dihitung)
        assertThat(summary.oldestDueDate).isEqualTo(minusDays(10)) // tertua di antara yang menunggak
        assertThat(summary.lastPaidAt).isEqualTo(paidAt)
    }

    @Test
    fun `tanpa tagihan mengembalikan ringkasan nol`() {
        val service = billing(FakeInvoiceRepository(emptyList()))

        val summary = service.findAccountSummary(customerId)

        assertThat(summary.outstandingAmount).isEqualByComparingTo("0")
        assertThat(summary.outstandingCount).isZero()
        assertThat(summary.unpaidCount).isZero()
        assertThat(summary.oldestDueDate).isNull()
        assertThat(summary.lastPaidAt).isNull()
    }

    @Test
    fun `lastPaidAt memilih pembayaran terbaru lintas tagihan`() {
        val older = Instant.parse("2026-06-15T02:00:00Z")
        val newer = Instant.parse("2026-07-20T02:00:00Z")
        val service = billing(
            FakeInvoiceRepository(
                listOf(
                    paid(amount = "100000", dueDate = minusDays(40), paidAt = older),
                    paid(amount = "100000", dueDate = minusDays(10), paidAt = newer),
                ),
            ),
        )

        val summary = service.findAccountSummary(customerId)

        assertThat(summary.lastPaidAt).isEqualTo(newer)
    }

    @Test
    fun `financialReport menjumlah tertagih, terbit, tunggakan, dan cacah status`() {
        val service = billing(
            FakeInvoiceRepository(
                invoices = emptyList(),
                paid = listOf(paidOn("120000", "2026-07-05T12:00:00Z"), paidOn("80000", "2026-07-20T12:00:00Z")),
                issued = listOf(issued("200000", minusDays(1)), issued("50000", plusDays(3))),
                outstanding = listOf(overdue("75000", minusDays(10)), issued("25000", minusDays(2))),
                statusCounts = mapOf(
                    InvoiceStatus.PAID to 2L,
                    InvoiceStatus.ISSUED to 3L,
                    InvoiceStatus.OVERDUE to 1L,
                ),
            ),
        )

        val report = service.financialReport(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))

        assertThat(report.revenueCollected).isEqualByComparingTo("200000") // 120000 + 80000
        assertThat(report.paidInvoiceCount).isEqualTo(2)
        assertThat(report.issuedAmount).isEqualByComparingTo("250000") // 200000 + 50000
        assertThat(report.issuedInvoiceCount).isEqualTo(2)
        assertThat(report.outstandingAmount).isEqualByComparingTo("100000") // 75000 + 25000
        assertThat(report.outstandingInvoiceCount).isEqualTo(2)
        assertThat(report.statusCounts).containsEntry("PAID", 2).containsEntry("ISSUED", 3).containsEntry("OVERDUE", 1)
    }

    @Test
    fun `monthlyRevenue mengelompokkan per bulan dan menebar bolong jadi nol`() {
        val service = billing(
            FakeInvoiceRepository(
                invoices = emptyList(),
                paid = listOf(
                    paidOn("100000", "2026-07-10T12:00:00Z"),
                    paidOn("40000", "2026-07-25T12:00:00Z"),
                    paidOn("60000", "2026-08-03T12:00:00Z"),
                ),
            ),
        )

        val series = service.monthlyRevenue(java.time.YearMonth.of(2026, 5), java.time.YearMonth.of(2026, 8))

        assertThat(series.map { it.month }).containsExactly("2026-05", "2026-06", "2026-07", "2026-08")
        assertThat(series[0].revenue).isEqualByComparingTo("0")
        assertThat(series[1].revenue).isEqualByComparingTo("0")
        assertThat(series[2].revenue).isEqualByComparingTo("140000") // Jul: 100000 + 40000
        assertThat(series[2].paidInvoiceCount).isEqualTo(2)
        assertThat(series[3].revenue).isEqualByComparingTo("60000") // Agu
        assertThat(series[3].paidInvoiceCount).isEqualTo(1)
    }

    // --- Perkakas uji ---

    /** Ringkasan rekening tak menyentuh pembayaran; fake payment repo kosong sudah cukup. */
    private fun billing(invoices: InvoiceRepository) = BillingApiService(invoices, FakePaymentRepository())

    private fun paidOn(amount: String, paidAt: String): Invoice =
        paid(amount = amount, dueDate = minusDays(1), paidAt = Instant.parse(paidAt))

    private fun issued(amount: String, dueDate: LocalDate): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = customerId,
        subscriptionId = UuidV7.generate(),
        number = "INV-${UuidV7.generate()}",
        periodStart = LocalDate.of(2026, 7, 1),
        periodEnd = LocalDate.of(2026, 7, 31),
        amount = BigDecimal(amount),
        dueDate = dueDate,
    )

    private fun overdue(amount: String, dueDate: LocalDate): Invoice =
        issued(amount, dueDate).apply { markOverdue() }

    private fun paid(amount: String, dueDate: LocalDate, paidAt: Instant): Invoice =
        issued(amount, dueDate).apply { markPaid(paidAt) }

    private fun voided(amount: String, dueDate: LocalDate): Invoice =
        issued(amount, dueDate).apply { void() }

    private fun plusDays(n: Long): LocalDate = LocalDate.now().plusDays(n)
    private fun minusDays(n: Long): LocalDate = LocalDate.now().minusDays(n)

    private class FakeInvoiceRepository(
        private val invoices: List<Invoice>,
        private val paid: List<Invoice> = emptyList(),
        private val issued: List<Invoice> = emptyList(),
        private val outstanding: List<Invoice> = emptyList(),
        private val statusCounts: Map<InvoiceStatus, Long> = emptyMap(),
    ) : InvoiceRepository {
        override fun findByCustomerId(customerId: UUID): List<Invoice> = invoices
        override fun findPaidBetween(from: Instant, toExclusive: Instant): List<Invoice> = paid
        override fun findIssuedBetween(from: Instant, toExclusive: Instant): List<Invoice> = issued
        override fun findOutstanding(asOf: LocalDate): List<Invoice> = outstanding
        override fun countByStatus(): Map<InvoiceStatus, Long> = statusCounts

        override fun save(invoice: Invoice) = throw UnsupportedOperationException()
        override fun findById(id: UUID) = throw UnsupportedOperationException()
        override fun findAll() = throw UnsupportedOperationException()
        override fun findByNumber(number: String) = throw UnsupportedOperationException()
        override fun findByStatus(status: InvoiceStatus) = throw UnsupportedOperationException()
        override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun countForPeriod(periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun findBillableOverdue(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun findRemindableDueSoon(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
        override fun hasOverdueForSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
    }

    private class FakePaymentRepository(private val payments: List<Payment> = emptyList()) : PaymentRepository {
        override fun findByCustomerId(customerId: UUID): List<Payment> = payments
        override fun findByInvoiceId(invoiceId: UUID) = throw UnsupportedOperationException()
        override fun save(payment: Payment) = throw UnsupportedOperationException()
    }
}
