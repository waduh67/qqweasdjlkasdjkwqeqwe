package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.ManualPaymentInstructionsView
import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.port.outbound.RefundRepository
import com.duluin.ftth.billing.application.service.ActiveGatewayProbe
import com.duluin.ftth.billing.application.service.BillingApiService
import com.duluin.ftth.billing.application.service.InvoiceChargePort
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.billing.domain.model.Refund
import com.duluin.ftth.billing.domain.model.RefundReason
import com.duluin.ftth.billing.domain.model.RefundStatus
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.UpdateCustomerBiodataCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 *
 * Plus jalur HALAMAN BAYAR PUBLIK (`findInvoiceForPublicLink`/`payInvoiceForPublicLink`):
 * proyeksinya tak boleh membocorkan referensi gateway, dan instruksi bayar yang masih hidup
 * wajib dipakai ulang — tautannya dipegang siapa saja, jadi tiap muat ulang halaman tak boleh
 * menghambur sesi bayar baru di penyedia.
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
    fun `refund yang selesai mengurangi pendapatan bersih tanpa mengubah yang bruto`() {
        val service = billing(
            FakeInvoiceRepository(
                invoices = emptyList(),
                paid = listOf(paidOn("120000", "2026-07-05T12:00:00Z"), paidOn("80000", "2026-07-20T12:00:00Z")),
            ),
            refunds = listOf(
                settledRefund("50000", "2026-07-10T12:00:00Z"),
                settledRefund("30000", "2026-07-28T12:00:00Z"),
                settledRefund("999000", "2026-08-02T12:00:00Z"), // di luar rentang → tak ikut
            ),
        )

        val report = service.financialReport(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))

        assertThat(report.revenueCollected).isEqualByComparingTo("200000") // bruto tetap apa adanya
        assertThat(report.refundedAmount).isEqualByComparingTo("80000") // 50000 + 30000
        assertThat(report.refundCount).isEqualTo(2)
        assertThat(report.netRevenue).isEqualByComparingTo("120000") // 200000 − 80000
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

    @Test
    fun `refund jatuh di bulan uangnya keluar, bukan bulan tagihannya lunas`() {
        val service = billing(
            FakeInvoiceRepository(invoices = emptyList(), paid = listOf(paidOn("100000", "2026-07-10T12:00:00Z"))),
            // Dibayar Juli, dikembalikan Agustus — refundnya harus muncul di Agustus.
            refunds = listOf(settledRefund("40000", "2026-08-04T12:00:00Z")),
        )

        val series = service.monthlyRevenue(java.time.YearMonth.of(2026, 7), java.time.YearMonth.of(2026, 8))

        assertThat(series[0].revenue).isEqualByComparingTo("100000")
        assertThat(series[0].refunded).isEqualByComparingTo("0")
        assertThat(series[1].revenue).isEqualByComparingTo("0")
        assertThat(series[1].refunded).isEqualByComparingTo("40000")
    }

    // --- Halaman bayar publik ---

    @Test
    fun `proyeksi publik tak membawa referensi gateway maupun tautan penyedia`() {
        val invoice = issued("150000", plusDays(5)).apply {
            attachCharge(provider = "PIVOT", gatewayRef = "SESI-RAHASIA", payUrl = "https://pivot.test/pay/abc")
        }
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice))

        val ref = service.findInvoiceForPublicLink(invoice.id)!!

        assertThat(ref.payableOnline).isTrue()
        assertThat(ref.customerName).isEqualTo("Budi")
        assertThat(ref.manual).isNull()
        // Bentuk DTO-nya sendiri yang menjaga: bidang rahasia tak ada sama sekali, jadi tak
        // mungkin bocor karena kelalaian pemetaan di kemudian hari.
        assertThat(PublicInvoiceRef::class.java.declaredFields.map { it.name })
            .doesNotContain("gatewayRef", "payUrl", "paymentSessionId", "qrUrl")
    }

    @Test
    fun `gateway MANUAL memulangkan instruksi transfer alih-alih panel bayar online`() {
        val invoice = issued("150000", plusDays(5))
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice), probe = ManualProbe, settings = ManualSettings)

        val ref = service.findInvoiceForPublicLink(invoice.id)!!

        assertThat(ref.payableOnline).isFalse()
        assertThat(ref.manual?.bankName).isEqualTo("BCA")
        assertThat(ref.manual?.accountNumber).isEqualTo("1234567890")
    }

    @Test
    fun `tagihan lunas tak bisa dibayar lewat tautan publik`() {
        val invoice = paid(amount = "150000", dueDate = minusDays(3), paidAt = Instant.now())
        val charger = CountingCharger()
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice), charger = charger)

        assertThat(service.findInvoiceForPublicLink(invoice.id)!!.payableOnline).isFalse()
        assertThatThrownBy { service.payInvoiceForPublicLink(invoice.id, "QR", null) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(charger.calls).isZero()
    }

    @Test
    fun `tagihan batal tak bisa dibayar lewat tautan publik`() {
        val invoice = voided(amount = "150000", dueDate = minusDays(3))
        val charger = CountingCharger()
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice), charger = charger)

        assertThat(service.findInvoiceForPublicLink(invoice.id)!!.payableOnline).isFalse()
        assertThatThrownBy { service.payInvoiceForPublicLink(invoice.id, "QR", null) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(charger.calls).isZero()
    }

    @Test
    fun `instruksi VA yang masih hidup dipakai ulang, bukan bikin sesi bayar baru`() {
        val invoice = issued("150000", plusDays(5))
        val charger = CountingCharger()
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice), charger = charger)

        val first = service.payInvoiceForPublicLink(invoice.id, "VIRTUAL_ACCOUNT", "BRI")
        val second = service.payInvoiceForPublicLink(invoice.id, "VIRTUAL_ACCOUNT", "BRI")

        assertThat(charger.calls).isEqualTo(1)
        assertThat(second.vaNumber).isEqualTo(first.vaNumber)
    }

    @Test
    fun `ganti bank VA membuat charge baru`() {
        val invoice = issued("150000", plusDays(5))
        val charger = CountingCharger()
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice), charger = charger)

        service.payInvoiceForPublicLink(invoice.id, "VIRTUAL_ACCOUNT", "BRI")
        val second = service.payInvoiceForPublicLink(invoice.id, "VIRTUAL_ACCOUNT", "BNI")

        assertThat(charger.calls).isEqualTo(2)
        assertThat(second.vaChannel).isEqualTo("BNI")
    }

    @Test
    fun `instruksi yang sudah kedaluwarsa memicu charge baru`() {
        val invoice = issued("150000", plusDays(5))
        val expired = CountingCharger(expiresAt = Instant.now().minusSeconds(60))
        val service = billing(FakeInvoiceRepository(emptyList(), stored = invoice), charger = expired)

        service.payInvoiceForPublicLink(invoice.id, "VIRTUAL_ACCOUNT", "BRI")
        service.payInvoiceForPublicLink(invoice.id, "VIRTUAL_ACCOUNT", "BRI")

        assertThat(expired.calls).isEqualTo(2)
    }

    @Test
    fun `tagihan di luar tenant aktif tak terlihat dari tautan publik`() {
        val service = billing(FakeInvoiceRepository(emptyList(), stored = null))

        assertThat(service.findInvoiceForPublicLink(UuidV7.generate())).isNull()
    }

    // --- Perkakas uji ---

    /** Ringkasan rekening tak menyentuh pembayaran/charge; fake payment repo & charger no-op cukup. */
    private fun billing(
        invoices: InvoiceRepository,
        charger: InvoiceChargePort = NoopCharger,
        probe: ActiveGatewayProbe = PivotProbe,
        settings: ManagePaymentGatewaySettingsUseCase = StubSettings,
        refunds: List<Refund> = emptyList(),
    ) = BillingApiService(
        invoices,
        FakePaymentRepository(),
        FakeRefundRepository(refunds),
        charger,
        StubCustomerApi,
        probe,
        settings,
    )

    /** Refund yang sudah selesai pada [completedAt] — satu-satunya bentuk yang masuk laporan. */
    private fun settledRefund(amount: String, completedAt: String): Refund = Refund.rehydrate(
        id = UuidV7.generate(),
        tenantId = UuidV7.generate(),
        invoiceId = UuidV7.generate(),
        customerId = customerId,
        paymentId = null,
        amount = BigDecimal(amount),
        reason = RefundReason.REQUESTED_BY_CUSTOMER,
        provider = "PIVOT",
        note = null,
        status = RefundStatus.SUCCESS,
        gatewayRef = "rfn-test",
        failureReason = null,
        requestedAt = Instant.parse(completedAt).minusSeconds(3600),
        completedAt = Instant.parse(completedAt),
    )

    private fun paidOn(amount: String, paidAt: String): Invoice =
        paid(amount = amount, dueDate = minusDays(1), paidAt = Instant.parse(paidAt))

    private fun issued(amount: String, dueDate: LocalDate): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = customerId,
        subscriptionId = UuidV7.generate(),
        number = "INV-${UuidV7.generate()}",
        periodStart = LocalDate.of(2026, 7, 1),
        periodEnd = LocalDate.of(2026, 7, 31),
        baseAmount = BigDecimal(amount),
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

    /**
     * [stored] adalah satu-satunya tagihan yang bisa dicari by-id — cukup untuk jalur tautan publik,
     * sekaligus mewakili RLS: `null` = tagihan itu bukan milik tenant aktif, jadi tak terlihat.
     */
    private class FakeInvoiceRepository(
        private val invoices: List<Invoice>,
        private val paid: List<Invoice> = emptyList(),
        private val issued: List<Invoice> = emptyList(),
        private val outstanding: List<Invoice> = emptyList(),
        private val statusCounts: Map<InvoiceStatus, Long> = emptyMap(),
        private var stored: Invoice? = null,
    ) : InvoiceRepository {
        override fun findByCustomerId(customerId: UUID): List<Invoice> = invoices
        override fun findPaidBetween(from: Instant, toExclusive: Instant): List<Invoice> = paid
        override fun findIssuedBetween(from: Instant, toExclusive: Instant): List<Invoice> = issued
        override fun findOutstanding(asOf: LocalDate): List<Invoice> = outstanding
        override fun countByStatus(): Map<InvoiceStatus, Long> = statusCounts
        override fun findById(id: UUID): Invoice? = stored?.takeIf { it.id == id }

        override fun save(invoice: Invoice): Invoice {
            stored = invoice
            return invoice
        }

        override fun findAll() = throw UnsupportedOperationException()
        override fun findByNumber(number: String) = throw UnsupportedOperationException()
        override fun findByStatus(status: InvoiceStatus) = throw UnsupportedOperationException()
        override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun countForPeriod(periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun findBillableOverdue(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun findRemindableDueSoon(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
        override fun hasOverdueForSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
    }

    /** Pelanggan tak dipakai uji ringkasan/laporan — hanya jalur halaman bayar publik yang menanyakannya. */
    private object StubCustomerApi : CustomerApi {
        override fun findCustomer(id: UUID) = CustomerRef(
            id = id, code = "C-001", name = "Budi", phone = null, email = null,
            location = Coordinate(0.0, 0.0), status = "ACTIVE",
        )

        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findSubscriptionByCustomer(customerId: UUID) = throw UnsupportedOperationException()
        override fun findOccupantsOfOdp(odpId: UUID) = throw UnsupportedOperationException()
        override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
        override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
        override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findOnusBySerialNumbers(serialNumbers: Set<String>) = throw UnsupportedOperationException()
        override fun placementsForOnus(onuIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
        override fun provisionOnu(command: ProvisionOnuCommand) = throw UnsupportedOperationException()
        override fun findBillableSubscriptions() = throw UnsupportedOperationException()
        override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun registerCustomer(command: RegisterCustomerCommand) = throw UnsupportedOperationException()
        override fun updateCustomerBiodata(command: UpdateCustomerBiodataCommand) =
            throw UnsupportedOperationException()

        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: Instant?, billingDayOfMonth: Int?) =
            throw UnsupportedOperationException()

        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) =
            throw UnsupportedOperationException()

        override fun subscriberStats() = throw UnsupportedOperationException()
        override fun subscriptionDimensions(subscriptionIds: Set<java.util.UUID>) = throw UnsupportedOperationException()
        override fun churnReport(from: java.time.LocalDate, to: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<UUID>) = throw UnsupportedOperationException()
    }

    /** Gateway aktif non-MANUAL: bayar in-app tersedia. */
    private object PivotProbe : ActiveGatewayProbe {
        override fun manualOnly() = false
    }

    /** Tenant memilih transfer manual: panel VA/QRIS tak layak, instruksi transfer yang tampil. */
    private object ManualProbe : ActiveGatewayProbe {
        override fun manualOnly() = true
    }

    private object ManualSettings : ManagePaymentGatewaySettingsUseCase by StubSettings {
        override fun manualPaymentInstructions() = ManualPaymentInstructionsView(
            transferEnabled = true,
            bankName = "BCA",
            accountNumber = "1234567890",
            accountHolder = "PT Mynet",
            qrisEnabled = false,
            qrisImageAvailable = false,
        )
    }

    /**
     * Mencatat berapa kali gateway diminta membuka sesi bayar — inti uji pakai-ulang instruksi.
     * Melekatkan instruksi palsu persis seperti adapter Pivot, berlaku sampai [expiresAt].
     */
    private class CountingCharger(
        private val expiresAt: Instant = Instant.now().plusSeconds(3600),
    ) : InvoiceChargePort {
        var calls = 0
            private set

        override fun chargeWithMethod(invoice: Invoice, method: String, channel: String?) {
            calls++
            val (m, ch) = PaymentMethodCatalog.normalize(method, channel)
            if (m == PaymentMethodCatalog.METHOD_VA) {
                invoice.attachInstruction(
                    provider = "PIVOT", gatewayRef = "REF-$calls", method = m, vaChannel = ch,
                    vaNumber = "8881000$calls", vaName = "PT Mynet", vaExpiresAt = expiresAt,
                    qrContent = null, qrUrl = null, qrExpiresAt = null,
                )
            } else {
                invoice.attachInstruction(
                    provider = "PIVOT", gatewayRef = "REF-$calls", method = m, vaChannel = null,
                    vaNumber = null, vaName = null, vaExpiresAt = null,
                    qrContent = "00020101021226$calls", qrUrl = null, qrExpiresAt = expiresAt,
                )
            }
        }
    }

    /** Setelan gateway tak disentuh selama gateway-nya bukan MANUAL. */
    private object StubSettings : ManagePaymentGatewaySettingsUseCase {
        override fun get() = throw UnsupportedOperationException()
        override fun update(command: UpdatePaymentGatewaySettingsCommand) = throw UnsupportedOperationException()
        override fun uploadQrisImage(contentType: String, bytes: ByteArray) = throw UnsupportedOperationException()
        override fun deleteQrisImage() = throw UnsupportedOperationException()
        override fun getQrisImage() = throw UnsupportedOperationException()
        override fun manualPaymentInstructions() = throw UnsupportedOperationException()
    }

    /** Charger no-op: uji ringkasan/laporan tak pernah membuat charge. */
    private object NoopCharger : InvoiceChargePort {
        override fun chargeWithMethod(invoice: Invoice, method: String, channel: String?) =
            throw UnsupportedOperationException()
    }

    private class FakePaymentRepository(private val payments: List<Payment> = emptyList()) : PaymentRepository {
        override fun findByCustomerId(customerId: UUID): List<Payment> = payments
        override fun findByInvoiceId(invoiceId: UUID) = throw UnsupportedOperationException()
        override fun save(payment: Payment) = throw UnsupportedOperationException()
    }

    /** Hanya jalur laporan yang dipakai: refund selesai disaring menurut [Refund.completedAt]. */
    private class FakeRefundRepository(private val refunds: List<Refund> = emptyList()) : RefundRepository {
        override fun findSettledBetween(from: Instant, toExclusive: Instant): List<Refund> =
            refunds.filter { it.completedAt != null && it.completedAt!! >= from && it.completedAt!! < toExclusive }

        override fun save(refund: Refund) = throw UnsupportedOperationException()
        override fun findById(id: UUID) = throw UnsupportedOperationException()
        override fun findByInvoiceId(invoiceId: UUID) = throw UnsupportedOperationException()
        override fun findByReference(reference: String) = throw UnsupportedOperationException()
        override fun findAll() = throw UnsupportedOperationException()
    }
}
