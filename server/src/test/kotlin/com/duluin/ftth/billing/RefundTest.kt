package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.inbound.RequestRefundCommand
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.application.port.outbound.RefundRepository
import com.duluin.ftth.billing.application.port.outbound.RefundRequest
import com.duluin.ftth.billing.application.port.outbound.RefundResult
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.application.service.RefundService
import com.duluin.ftth.billing.application.service.TenantPaymentGatewayResolver
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.Refund
import com.duluin.ftth.billing.domain.model.RefundReason
import com.duluin.ftth.billing.domain.model.RefundStatus
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Menguji pengembalian dana: sisi domain ([Refund] + [Invoice.applyRefund]) dan sisi alur
 * ([RefundService]) dengan fake murni — tanpa Spring maupun DB.
 *
 * Yang dijaga di sini semuanya soal UANG KELUAR, jadi penjaganya berlapis dengan sengaja:
 * tagihan tak boleh dikembalikan melebihi nilainya, permintaan yang masih berjalan ikut menutup
 * kuota (dua permintaan penuh berturut-turut tak boleh sama-sama lolos), dan callback ganda tak
 * boleh menjumlah pengembalian dua kali.
 */
class RefundTest {

    // --- Domain: sisi tagihan ---

    @Test
    fun `applyRefund penuh memindahkan tagihan lunas ke REFUNDED`() {
        val invoice = paidInvoice("150000")

        invoice.applyRefund(BigDecimal("150000"))

        assertThat(invoice.status).isEqualTo(InvoiceStatus.REFUNDED)
        assertThat(invoice.refundedAmount).isEqualByComparingTo("150000")
        assertThat(invoice.refundableAmount).isEqualByComparingTo("0")
    }

    @Test
    fun `refund sebagian membiarkan tagihan tetap PAID dan menyisakan kuota`() {
        val invoice = paidInvoice("150000")

        invoice.applyRefund(BigDecimal("50000"))

        assertThat(invoice.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(invoice.refundableAmount).isEqualByComparingTo("100000")
    }

    @Test
    fun `refund melebihi nilai tagihan ditolak`() {
        val invoice = paidInvoice("150000").apply { applyRefund(BigDecimal("100000")) }

        assertThatThrownBy { invoice.applyRefund(BigDecimal("60000")) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(invoice.refundedAmount).isEqualByComparingTo("100000")
    }

    @Test
    fun `tagihan yang belum lunas tak bisa dikembalikan`() {
        assertThatThrownBy { newInvoice("150000").applyRefund(BigDecimal("10000")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `tagihan yang sudah dikembalikan penuh tak bisa ditandai lunas maupun dibatalkan lagi`() {
        val invoice = paidInvoice("150000").apply { applyRefund(BigDecimal("150000")) }

        assertThatThrownBy { invoice.markPaid(Instant.now()) }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { invoice.void() }.isInstanceOf(ConflictException::class.java)
    }

    // --- Domain: sisi baris refund ---

    @Test
    fun `refund baru mulai PENDING dengan nominal berskala dua`() {
        val refund = newRefund("75000.5")

        assertThat(refund.status).isEqualTo(RefundStatus.PENDING)
        assertThat(refund.amount.scale()).isEqualTo(2)
        assertThat(refund.gatewayRef).isNull()
        assertThat(refund.settled).isFalse()
    }

    @Test
    fun `nominal nol atau negatif ditolak`() {
        assertThatThrownBy { newRefund("0") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { newRefund("-1000") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `markProcessing melekatkan ref penyedia dan menaikkan status`() {
        val refund = newRefund("50000").apply { markProcessing("rfn_123") }

        assertThat(refund.status).isEqualTo(RefundStatus.PROCESSING)
        assertThat(refund.gatewayRef).isEqualTo("rfn_123")
        assertThat(refund.status.open).isTrue()
    }

    @Test
    fun `kegagalan yang datang setelah berhasil tak menghapus uang yang sudah pulang`() {
        val at = Instant.parse("2026-08-01T00:00:00Z")
        val refund = newRefund("50000").apply {
            markProcessing("rfn_123")
            markSuccess(at)
            markFailed("timeout penyedia", at.plusSeconds(60))
        }

        assertThat(refund.status).isEqualTo(RefundStatus.SUCCESS)
        assertThat(refund.failureReason).isNull()
        assertThat(refund.completedAt).isEqualTo(at)
    }

    // --- Alur: pengajuan ---

    @Test
    fun `pengajuan lewat penyedia mengirim perintah dan berhenti di PROCESSING sampai callback`() {
        val f = fixture(invoice = paidByPivot("150000"))

        val view = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        assertThat(view.status).isEqualTo("PROCESSING")
        assertThat(view.provider).isEqualTo("PIVOT")
        assertThat(view.amount).isEqualByComparingTo("150000") // nominal kosong = seluruh sisa
        assertThat(f.gateway.requests).hasSize(1)
        with(f.gateway.requests.first()) {
            assertThat(paymentSessionId).isEqualTo("SESI-PIVOT")
            assertThat(fullAmount).isTrue()
            assertThat(referenceId).isEqualTo(view.id.toString()) // dipulangkan callback
        }
        // Tagihannya BELUM bergerak — uangnya belum benar-benar kembali.
        assertThat(f.invoice.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(f.invoice.refundedAmount).isEqualByComparingTo("0")
    }

    @Test
    fun `penyedia yang menyatakan selesai seketika langsung menggerakkan tagihan`() {
        val f = fixture(invoice = paidByPivot("150000"), gateway = CapturingGateway(settled = true))

        val view = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        assertThat(view.status).isEqualTo("SUCCESS")
        assertThat(f.invoice.status).isEqualTo(InvoiceStatus.REFUNDED)
        assertThat(f.invoice.refundedAmount).isEqualByComparingTo("150000")
    }

    @Test
    fun `tagihan yang belum lunas tak bisa diajukan refund`() {
        val f = fixture(invoice = newInvoice("150000"))

        assertThatThrownBy { f.service.request(RequestRefundCommand(invoiceId = f.invoice.id)) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(f.gateway.requests).isEmpty()
    }

    @Test
    fun `permintaan yang masih berjalan menutup kuota permintaan berikutnya`() {
        val f = fixture(invoice = paidByPivot("150000"))
        f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        assertThatThrownBy { f.service.request(RequestRefundCommand(invoiceId = f.invoice.id)) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(f.gateway.requests).hasSize(1) // penyedia tak dipanggil dua kali
    }

    @Test
    fun `refund sebagian menyisakan kuota untuk pengajuan berikutnya`() {
        val f = fixture(invoice = paidByPivot("150000"), gateway = CapturingGateway(settled = true))

        f.service.request(RequestRefundCommand(invoiceId = f.invoice.id, amount = BigDecimal("50000")))
        val second = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        assertThat(second.amount).isEqualByComparingTo("100000") // sisa
        assertThat(f.invoice.status).isEqualTo(InvoiceStatus.REFUNDED)
        assertThat(f.gateway.requests.map { it.fullAmount }).containsExactly(false, false)
    }

    @Test
    fun `nominal melebihi sisa ditolak sebelum menyentuh penyedia`() {
        val f = fixture(invoice = paidByPivot("150000"))

        assertThatThrownBy {
            f.service.request(RequestRefundCommand(invoiceId = f.invoice.id, amount = BigDecimal("200000")))
        }.isInstanceOf(ConflictException::class.java)
        assertThat(f.gateway.requests).isEmpty()
    }

    @Test
    fun `tagihan tanpa jejak sesi bayar penyedia ditolak, bukan dikirim buta`() {
        // Dibayar lewat PIVOT tapi tak ada gatewayRef (mis. data lama) → tak ada tujuan refund.
        val f = fixture(invoice = paidInvoice("150000", provider = "PIVOT", gatewayRef = null))

        assertThatThrownBy { f.service.request(RequestRefundCommand(invoiceId = f.invoice.id)) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(f.gateway.requests).isEmpty()
    }

    @Test
    fun `tagihan yang dibayar manual berhenti di PENDING tanpa memanggil penyedia`() {
        val f = fixture(invoice = paidInvoice("150000", provider = "MANUAL", gatewayRef = null))

        val view = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        assertThat(view.provider).isEqualTo("MANUAL")
        assertThat(view.status).isEqualTo("PENDING")
        assertThat(f.gateway.requests).isEmpty()
    }

    @Test
    fun `penyedia diambil dari cara tagihan dibayar, bukan gateway yang aktif sekarang`() {
        // Tagihan lama dibayar transfer bank; hari ini tenant memakai PIVOT — uangnya tetap pulang manual.
        val invoice = paidInvoice("150000", provider = "BANK_TRANSFER", gatewayRef = null)
        val f = fixture(invoice = invoice, payments = listOf(payment(invoice, provider = "MANUAL")))

        val view = f.service.request(RequestRefundCommand(invoiceId = invoice.id))

        assertThat(view.provider).isEqualTo("MANUAL")
        assertThat(f.gateway.requests).isEmpty()
    }

    // --- Alur: penutupan ---

    @Test
    fun `penutupan manual yang berhasil menggerakkan tagihan`() {
        val f = fixture(invoice = paidInvoice("150000", provider = "MANUAL", gatewayRef = null))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        val settled = f.service.settleManual(requested.id, success = true, reason = null)

        assertThat(settled.status).isEqualTo("SUCCESS")
        assertThat(f.invoice.status).isEqualTo(InvoiceStatus.REFUNDED)
    }

    @Test
    fun `penutupan manual yang gagal mengembalikan kuota refund tagihan`() {
        val f = fixture(invoice = paidInvoice("150000", provider = "MANUAL", gatewayRef = null))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        f.service.settleManual(requested.id, success = false, reason = "rekening pelanggan tutup")

        assertThat(f.invoice.refundedAmount).isEqualByComparingTo("0")
        // Kuota terbuka lagi: pengajuan ulang penuh boleh.
        assertThat(f.service.request(RequestRefundCommand(invoiceId = f.invoice.id)).amount)
            .isEqualByComparingTo("150000")
    }

    @Test
    fun `baris penyedia tak boleh ditutup tangan`() {
        val f = fixture(invoice = paidByPivot("150000"))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        assertThatThrownBy { f.service.settleManual(requested.id, success = true, reason = null) }
            .isInstanceOf(ConflictException::class.java)
    }

    // --- Alur: rekonsiliasi callback ---

    @Test
    fun `callback sukses menutup baris dan menggerakkan tagihan`() {
        val f = fixture(invoice = paidByPivot("150000"))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        f.service.reconcile(reference = "rfn-${requested.id}", clientReference = null, success = true, reason = null)

        assertThat(f.refunds.stored(requested.id).status).isEqualTo(RefundStatus.SUCCESS)
        assertThat(f.invoice.status).isEqualTo(InvoiceStatus.REFUNDED)
    }

    @Test
    fun `callback sukses kembar tak menjumlah pengembalian dua kali`() {
        val f = fixture(invoice = paidByPivot("150000"))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))
        val ref = "rfn-${requested.id}"

        f.service.reconcile(ref, null, success = true, reason = null)
        f.service.reconcile(ref, null, success = true, reason = null)

        assertThat(f.invoice.refundedAmount).isEqualByComparingTo("150000")
    }

    @Test
    fun `callback gagal yang datang setelah sukses tak membatalkan pengembalian`() {
        val f = fixture(invoice = paidByPivot("150000"))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))
        val ref = "rfn-${requested.id}"

        f.service.reconcile(ref, null, success = true, reason = null)
        f.service.reconcile(ref, null, success = false, reason = "ditolak bank")

        assertThat(f.refunds.stored(requested.id).status).isEqualTo(RefundStatus.SUCCESS)
        assertThat(f.invoice.status).isEqualTo(InvoiceStatus.REFUNDED)
    }

    @Test
    fun `callback yang mendahului respons penyedia dikenali lewat id baris sendiri`() {
        // Baris MANUAL sengaja dipakai agar tak punya gatewayRef sama sekali — persis keadaan
        // "callback tiba sebelum ref penyedia sempat tersimpan".
        val f = fixture(invoice = paidInvoice("150000", provider = "MANUAL", gatewayRef = null))
        val requested = f.service.request(RequestRefundCommand(invoiceId = f.invoice.id))

        f.service.reconcile(reference = "rfn_baru", clientReference = requested.id.toString(), true, null)

        val stored = f.refunds.stored(requested.id)
        assertThat(stored.status).isEqualTo(RefundStatus.SUCCESS)
        assertThat(stored.gatewayRef).isEqualTo("rfn_baru") // ref-nya ikut dilekatkan
    }

    @Test
    fun `callback tanpa baris yang cocok diabaikan tanpa menyentuh tagihan`() {
        val f = fixture(invoice = paidByPivot("150000"))

        f.service.reconcile("rfn_asing", UuidV7.generate().toString(), success = true, reason = null)

        assertThat(f.invoice.refundedAmount).isEqualByComparingTo("0")
    }

    // --- Perkakas uji ---

    private class Fixture(
        val service: RefundService,
        val invoice: Invoice,
        val refunds: FakeRefundRepository,
        val gateway: CapturingGateway,
    )

    private fun fixture(
        invoice: Invoice,
        payments: List<Payment> = emptyList(),
        gateway: CapturingGateway = CapturingGateway(),
    ): Fixture {
        val props = BillingProperties()
        val refunds = FakeRefundRepository()
        val resolver = TenantPaymentGatewayResolver(
            NoGatewayConfig,
            NoPivotAccount,
            PivotMasterConfigProvider(NoMasterConfig),
            NoTenantApi,
            props,
        )
        val service = RefundService(
            refundRepository = refunds,
            invoiceRepository = FakeInvoiceRepository(invoice),
            paymentRepository = FakePaymentRepository(payments),
            registry = PaymentGatewayRegistry(listOf(gateway), props),
            gatewayResolver = resolver,
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
        )
        return Fixture(service, invoice, refunds, gateway)
    }

    private fun newInvoice(amount: String): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        number = "INV-202608-0001",
        periodStart = LocalDate.of(2026, 8, 1),
        periodEnd = LocalDate.of(2026, 8, 31),
        baseAmount = BigDecimal(amount),
        dueDate = LocalDate.of(2026, 8, 8),
    )

    private fun paidInvoice(amount: String, provider: String? = null, gatewayRef: String? = null): Invoice =
        newInvoice(amount).apply {
            provider?.let { attachCharge(provider = it, gatewayRef = gatewayRef, payUrl = null) }
            markPaid(Instant.parse("2026-08-05T02:00:00Z"))
        }

    /** Tagihan yang dilunasi lewat Pivot lengkap dengan referensi sesi bayarnya (tujuan refund). */
    private fun paidByPivot(amount: String): Invoice = paidInvoice(amount, provider = "PIVOT", gatewayRef = "SESI-PIVOT")

    private fun payment(invoice: Invoice, provider: String) = Payment.create(
        tenantId = invoice.tenantId,
        invoiceId = invoice.id,
        customerId = invoice.customerId,
        amount = invoice.amount,
        provider = provider,
        gatewayRef = null,
        paidAt = Instant.parse("2026-08-05T02:00:00Z"),
        note = null,
    )

    private fun newRefund(amount: String) = Refund.request(
        tenantId = UuidV7.generate(),
        invoiceId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        paymentId = null,
        amount = BigDecimal(amount),
        reason = RefundReason.REQUESTED_BY_CUSTOMER,
        provider = "PIVOT",
        note = null,
        requestedAt = Instant.parse("2026-08-06T00:00:00Z"),
    )

    /**
     * Gateway penangkap: mencatat perintah refund yang diterima dan mengembalikan ref deterministik
     * berbasis `clientReferenceId` agar test bisa menebak ref-nya saat menyusun callback.
     */
    private class CapturingGateway(private val settled: Boolean = false) : PaymentGateway {
        val requests = mutableListOf<RefundRequest>()
        override val provider = "PIVOT"

        override fun refund(request: RefundRequest, ctx: ResolvedGatewayContext): RefundResult {
            requests += request
            return RefundResult(
                reference = "rfn-${request.referenceId}",
                status = if (settled) "SUCCESS" else "PENDING",
                settled = settled,
            )
        }

        override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult =
            throw UnsupportedOperationException()

        override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? =
            throw UnsupportedOperationException()
    }

    /** Menyimpan objek domain apa adanya (bukan salinan) agar test bisa memeriksa keadaan terakhirnya. */
    private class FakeRefundRepository : RefundRepository {
        private val rows = linkedMapOf<UUID, Refund>()

        fun stored(id: UUID): Refund = rows.getValue(id)

        override fun save(refund: Refund): Refund = refund.also { rows[it.id] = it }
        override fun findById(id: UUID): Refund? = rows[id]
        override fun findByInvoiceId(invoiceId: UUID): List<Refund> = rows.values.filter { it.invoiceId == invoiceId }
        override fun findByReference(reference: String): Refund? = rows.values.find { it.gatewayRef == reference }
        override fun findAll(): List<Refund> = rows.values.toList()
        override fun findSettledBetween(from: Instant, toExclusive: Instant): List<Refund> =
            throw UnsupportedOperationException()
    }

    private class FakeInvoiceRepository(private val invoice: Invoice) : InvoiceRepository {
        override fun findById(id: UUID): Invoice? = invoice.takeIf { it.id == id }
        override fun save(invoice: Invoice): Invoice = invoice
        override fun findAll() = throw UnsupportedOperationException()
        override fun findByNumber(number: String) = throw UnsupportedOperationException()
        override fun findByCustomerId(customerId: UUID) = throw UnsupportedOperationException()
        override fun findByStatus(status: InvoiceStatus) = throw UnsupportedOperationException()
        override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate) =
            throw UnsupportedOperationException()

        override fun countForPeriod(periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun findBillableOverdue(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun findRemindableDueSoon(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
        override fun hasOverdueForSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun findPaidBetween(from: Instant, toExclusive: Instant) = throw UnsupportedOperationException()
        override fun findIssuedBetween(from: Instant, toExclusive: Instant) = throw UnsupportedOperationException()
        override fun findOutstanding(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun countByStatus() = throw UnsupportedOperationException()
    }

    private class FakePaymentRepository(private val payments: List<Payment>) : PaymentRepository {
        override fun findByInvoiceId(invoiceId: UUID): List<Payment> = payments.filter { it.invoiceId == invoiceId }
        override fun findByCustomerId(customerId: UUID) = throw UnsupportedOperationException()
        override fun save(payment: Payment) = throw UnsupportedOperationException()
    }

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull() = null
    }

    private object NoGatewayConfig : TenantPaymentGatewayRepository {
        override fun find(): TenantPaymentGateway? = null
        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway = settings
    }

    private object NoPivotAccount : TenantPivotAccountRepository {
        override fun find(): TenantPivotAccount? = null
        override fun save(account: TenantPivotAccount): TenantPivotAccount = account
        override fun findByTenant(tenantId: UUID): TenantPivotAccount? = null
    }

    private object NoMasterConfig : PivotMasterConfigRepository {
        override fun find(): PivotMasterConfig? = null
        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    /** Konteks gateway tak pernah dibaca gateway penangkap; TenantApi cuma dipakai cabang PIVOT. */
    private object NoTenantApi : TenantApi {
        override fun findById(id: UUID): TenantRef? = null
        override fun findBySlug(slug: String): TenantRef? = null
        override fun requireById(id: UUID): TenantRef = error("tak dipakai")
        override fun platformTenantId(): UUID = error("tak dipakai")
        override fun findActiveTenantIds(): List<UUID> = emptyList()
        override fun ensureTenant(slug: String, name: String): TenantRef = error("tak dipakai")
        override fun suspend(id: UUID): TenantRef = error("tak dipakai")
        override fun activate(id: UUID): TenantRef = error("tak dipakai")
    }
}
