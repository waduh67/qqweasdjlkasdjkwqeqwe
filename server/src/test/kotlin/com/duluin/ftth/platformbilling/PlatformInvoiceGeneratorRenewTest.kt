package com.duluin.ftth.platformbilling

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.application.port.outbound.QrInstruction
import com.duluin.ftth.billing.application.port.outbound.VaInstruction
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.billing.domain.model.SubAccountDefaults
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.UserRef
import com.duluin.ftth.platformbilling.application.port.inbound.ConfigureSubscriptionCommand
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.duluin.ftth.platformbilling.application.port.outbound.SubscriptionUsageProbe
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionPaymentRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.application.port.outbound.UsageCount
import com.duluin.ftth.platformbilling.application.service.PlatformGatewayResolver
import com.duluin.ftth.platformbilling.application.service.PlatformInvoiceGenerator
import com.duluin.ftth.platformbilling.application.service.PlatformPaymentService
import com.duluin.ftth.platformbilling.application.service.TenantSelfSubscriptionService
import com.duluin.ftth.platformbilling.application.service.TenantSubscriptionService
import com.duluin.ftth.platformbilling.domain.model.PlatformSetting
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionPayment
import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Regresi 400 "Tagihan tak dapat diterbitkan saat ini" saat perpanjang langganan: tabrakan nomor
 * bulanan `SUB-<yyyymm>-<tenant8>` dengan tagihan periode yang sudah VOID/PAID. Fake in-memory
 * penuh (repo + resolver/registry) tanpa Spring/DB — pola sama [PaymentGatewaySettingsServiceTest].
 */
class PlatformInvoiceGeneratorRenewTest {

    // tenant8 = "019fcadd" (8 hex pertama) → mereplikasi tenant produksi yang memicu bug.
    private val tenantId = UUID.fromString("019fcadd-0000-7000-8000-000000000000")
    private val subscriptionId = UuidV7.generate()

    // Perpanjangan menyambung dari ujung masa aktif → periode Okt 2026 → nomor SUB-202610-019fcadd.
    private val activeUntil = LocalDate.of(2026, 10, 4)
    private val today = LocalDate.of(2026, 8, 7)
    private val baseNumber = "SUB-202610-019fcadd"

    private companion object {
        // Email admin tenant yang di-resolve IamApi → wajib diteruskan ke Pivot (email pelanggan required).
        const val TENANT_EMAIL = "admin@tenant.test"
    }

    private lateinit var subscriptions: FakeSubscriptionRepository
    private lateinit var invoices: FakeInvoiceRepository
    private lateinit var generator: PlatformInvoiceGenerator

    @BeforeEach
    fun setUp() {
        subscriptions = FakeSubscriptionRepository()
        invoices = FakeInvoiceRepository()
        val resolver = PlatformGatewayResolver(FakePlatformSettingRepository(), PivotMasterConfigProvider(FakePivotRepository()))
        generator = PlatformInvoiceGenerator(
            subscriptionRepository = subscriptions,
            invoiceRepository = invoices,
            resolver = resolver,
            gatewayRegistry = PaymentGatewayRegistry(emptyList(), BillingProperties()),
            tenantApi = FakeTenantApi(),
            iamApi = FakeIamApi(TENANT_EMAIL),
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
        )
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `tagihan periode VOID diterbitkan ulang dengan nomor unik -R2`() {
        val subscription = activeSubscription()
        invoices.save(invoiceFor(baseNumber, SubscriptionInvoiceStatus.VOID))

        val issued = generator.issueFor(subscription, today, force = true, months = 1)

        assertThat(issued).isNotNull()
        assertThat(issued!!.number).isEqualTo("$baseNumber-R2")
        assertThat(issued.status).isEqualTo(SubscriptionInvoiceStatus.ISSUED)
        assertThat(issued.periodStart).isEqualTo(activeUntil)
        assertThat(issued.periodEnd).isEqualTo(LocalDate.of(2026, 11, 3))
        // Tagihan VOID lama tetap ada; yang baru bertambah (bukan menimpa).
        assertThat(invoices.all().map { it.number }).contains(baseNumber, "$baseNumber-R2")
    }

    @Test
    fun `tagihan periode belum lunas dikembalikan apa adanya (idempoten)`() {
        val subscription = activeSubscription()
        val existing = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED).also { invoices.save(it) }

        val issued = generator.issueFor(subscription, today, force = true, months = 1)

        assertThat(issued).isSameAs(existing)
        // Tak ada tagihan baru dibuat.
        assertThat(invoices.all()).hasSize(1)
    }

    @Test
    fun `tagihan periode sudah LUNAS tidak diterbitkan ulang (null)`() {
        val subscription = activeSubscription()
        invoices.save(invoiceFor(baseNumber, SubscriptionInvoiceStatus.PAID))

        val issued = generator.issueFor(subscription, today, force = true, months = 1)

        assertThat(issued).isNull()
        assertThat(invoices.all()).hasSize(1)
    }

    @Test
    fun `periode tanpa tagihan menerbitkan nomor SUB bulanan normal`() {
        val subscription = activeSubscription()

        val issued = generator.issueFor(subscription, today, force = true, months = 1)

        assertThat(issued).isNotNull()
        assertThat(issued!!.number).isEqualTo(baseNumber)
        assertThat(issued.status).isEqualTo(SubscriptionInvoiceStatus.ISSUED)
    }

    @Test
    fun `renew pada periode sudah lunas melempar pesan sudah dibayar (bukan pesan generik)`() {
        TenantContext.set(tenantId)
        val subscription = activeSubscription().also { subscriptions.save(it) }
        invoices.save(invoiceFor(baseNumber, SubscriptionInvoiceStatus.PAID))
        val service = selfService(generator)

        assertThatThrownBy { service.renew(months = 1) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("sudah dibayar")
    }

    @Test
    fun `renew pada tagihan tertunggak mengembalikan tagihan itu tanpa charge`() {
        TenantContext.set(tenantId)
        val subscription = activeSubscription().also { subscriptions.save(it) }
        // Tagihan tertunggak yang terbit tanpa instruksi bayar (metode dipilih tenant nanti).
        invoices.save(invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED))
        val service = selfService(chargingGenerator())

        val view = service.renew(months = 1)

        assertThat(view.number).isEqualTo(baseNumber)
        // Belum charge saat renew — instrumen bayar (VA/QRIS) dipilih tenant lewat "Bayar" (payInvoice).
        assertThat(view.payMethod).isNull()
        assertThat(view.payUrl).isNull()
        // Tagihan tertunggak yang sama dipakai ulang (tak terbit tagihan baru).
        assertThat(invoices.all()).hasSize(1)
    }

    // --- payInvoice (tombol "Bayar" per-tagihan di Riwayat tagihan) ---

    @Test
    fun `bayar tagihan tertunggak dengan QRIS menyimpan string QR in-app`() {
        TenantContext.set(tenantId)
        activeSubscription().also { subscriptions.save(it) }
        val invoice = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED).also { invoices.save(it) }
        val service = selfService(chargingGenerator())

        val view = service.payInvoice(invoice.id, "QR", null)

        assertThat(view.number).isEqualTo(baseNumber)
        assertThat(view.payMethod).isEqualTo("QR")
        assertThat(view.qrContent).isEqualTo("QRIS-$baseNumber")
        assertThat(view.payUrl).isNull() // mode API in-app — tanpa redirect
    }

    @Test
    fun `bayar tagihan tertunggak dengan Virtual Account menyimpan nomor VA dan bank`() {
        TenantContext.set(tenantId)
        activeSubscription().also { subscriptions.save(it) }
        val invoice = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED).also { invoices.save(it) }
        val service = selfService(chargingGenerator())

        val view = service.payInvoice(invoice.id, "VIRTUAL_ACCOUNT", "BRI")

        assertThat(view.payMethod).isEqualTo("VIRTUAL_ACCOUNT")
        assertThat(view.vaChannel).isEqualTo("BRI")
        assertThat(view.vaNumber).isEqualTo("8808$baseNumber")
        assertThat(view.payUrl).isNull()
    }

    @Test
    fun `bayar tagihan milik langganan lain ditolak (NotFound)`() {
        TenantContext.set(tenantId)
        activeSubscription().also { subscriptions.save(it) }
        // Tagihan milik subscriptionId lain → guard kepemilikan menolaknya.
        val alien = TenantSubscriptionInvoice.create(
            tenantId = tenantId,
            subscriptionId = UuidV7.generate(),
            number = "SUB-202611-019fbbbb",
            periodStart = activeUntil,
            periodEnd = LocalDate.of(2026, 11, 3),
            amount = BigDecimal("100000.00"),
            dueDate = activeUntil.plusDays(7),
        ).also { invoices.save(it) }
        val service = selfService(generator)

        assertThatThrownBy { service.payInvoice(alien.id, "QR", null) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `bayar tagihan yang sudah lunas ditolak (Validation)`() {
        TenantContext.set(tenantId)
        activeSubscription().also { subscriptions.save(it) }
        val paid = invoiceFor(baseNumber, SubscriptionInvoiceStatus.PAID).also { invoices.save(it) }
        val service = selfService(generator)

        assertThatThrownBy { service.payInvoice(paid.id, "QR", null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("tidak dapat dibayar")
    }

    @Test
    fun `bayar saat gateway belum dikonfigurasi melempar Conflict (bukan 500)`() {
        TenantContext.set(tenantId)
        activeSubscription().also { subscriptions.save(it) }
        val invoice = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED).also { invoices.save(it) }
        // `generator` default → resolver Pivot NONAKTIF → resolveActive() melempar ConflictException.
        // Aksi dipicu pengguna → kegagalan charge dilempar (bukan ditelan), controller memetakan 409.
        val service = selfService(generator)

        assertThatThrownBy { service.payInvoice(invoice.id, "QR", null) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `charge langganan meneruskan email admin tenant ke gateway (bukan null)`() {
        val subscription = activeSubscription().also { subscriptions.save(it) }
        val gateway = FakePivotGateway()
        val gen = PlatformInvoiceGenerator(
            subscriptionRepository = subscriptions,
            invoiceRepository = invoices,
            resolver = PlatformGatewayResolver(FakePlatformSettingRepository(), PivotMasterConfigProvider(EnabledPivotRepository())),
            gatewayRegistry = PaymentGatewayRegistry(listOf(gateway), BillingProperties()),
            tenantApi = FakeTenantApi(),
            iamApi = FakeIamApi(TENANT_EMAIL),
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
        )
        val invoice = gen.issueFor(subscription, today, force = true, months = 1)!!

        gen.chargeWithMethod(invoice, subscription, "QR", null)

        // Pivot menolak charge tanpa email → email admin tenant harus ikut, bukan null.
        assertThat(gateway.lastEmail).isEqualTo(TENANT_EMAIL)
    }

    // --- reprice (domain) & super-admin generateInvoice/configure ---

    @Test
    fun `reprice tagihan belum lunas tanpa charge mengubah nilai`() {
        val invoice = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED)

        val changed = invoice.reprice(BigDecimal("250000"))

        assertThat(changed).isTrue()
        assertThat(invoice.amount).isEqualByComparingTo("250000.00")
    }

    @Test
    fun `reprice tagihan yang sudah di-charge dilewati (tak desync gateway)`() {
        val invoice = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED)
        invoice.attachCharge(provider = "PIVOT", gatewayRef = "ref_1", payUrl = "https://pay.example")

        val changed = invoice.reprice(BigDecimal("250000"))

        assertThat(changed).isFalse()
        assertThat(invoice.amount).isEqualByComparingTo("100000.00")
    }

    @Test
    fun `reprice tagihan lunas atau dibatalkan dilewati`() {
        val paid = invoiceFor(baseNumber, SubscriptionInvoiceStatus.PAID)
        val void = invoiceFor(baseNumber, SubscriptionInvoiceStatus.VOID)

        assertThat(paid.reprice(BigDecimal("250000"))).isFalse()
        assertThat(void.reprice(BigDecimal("250000"))).isFalse()
        assertThat(paid.amount).isEqualByComparingTo("100000.00")
    }

    @Test
    fun `generateInvoice idempoten mengembalikan tagihan tertunggak yang ada`() {
        subscriptions.save(activeSubscription())
        val existing = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED).also { invoices.save(it) }
        val service = subscriptionService()

        val view = service.generateInvoice(tenantId)

        assertThat(view.id).isEqualTo(existing.id)
        // Tak ada tagihan baru dibuat pada klik berulang.
        assertThat(invoices.all()).hasSize(1)
    }

    @Test
    fun `configure biaya baru menyesuaikan tagihan belum lunas`() {
        subscriptions.save(activeSubscription())
        val invoice = invoiceFor(baseNumber, SubscriptionInvoiceStatus.ISSUED).also { invoices.save(it) }
        val service = subscriptionService()

        service.configure(
            tenantId,
            ConfigureSubscriptionCommand(monthlyFee = BigDecimal("250000"), billingDay = null, graceDays = null),
        )

        assertThat(invoices.findById(invoice.id)!!.amount).isEqualByComparingTo("250000.00")
    }

    // --- helper ---

    /** Service super-admin lengkap dgn fake (paymentService tak dipakai jalur yang diuji). */
    private fun subscriptionService() = TenantSubscriptionService(
        subscriptionRepository = subscriptions,
        invoiceRepository = invoices,
        invoiceGenerator = generator,
        paymentService = PlatformPaymentService(
            invoiceRepository = invoices,
            paymentRepository = FakePaymentRepository(),
            subscriptionRepository = subscriptions,
            tenantApi = FakeTenantApi(),
            auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
        ),
        tenantApi = FakeTenantApi(),
        masterConfig = PivotMasterConfigProvider(FakePivotRepository()),
        auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
    )

    /** Generator dengan gateway PIVOT aktif yang mengembalikan instruksi VA/QR — untuk menguji charge on-demand. */
    private fun chargingGenerator() = PlatformInvoiceGenerator(
        subscriptionRepository = subscriptions,
        invoiceRepository = invoices,
        resolver = PlatformGatewayResolver(FakePlatformSettingRepository(), PivotMasterConfigProvider(EnabledPivotRepository())),
        gatewayRegistry = PaymentGatewayRegistry(listOf(FakePivotGateway()), BillingProperties()),
        tenantApi = FakeTenantApi(),
        iamApi = FakeIamApi(TENANT_EMAIL),
        auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser),
    )

    private fun activeSubscription(): TenantSubscription = TenantSubscription.rehydrate(
        id = subscriptionId,
        tenantId = tenantId,
        monthlyFee = BigDecimal("100000.00"),
        status = SubscriptionStatus.ACTIVE,
        billingDay = null,
        graceDays = null,
        currentPeriodStart = activeUntil.minusMonths(1),
        currentPeriodEnd = activeUntil,
        nextInvoiceAt = activeUntil,
        activatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun invoiceFor(number: String, status: SubscriptionInvoiceStatus): TenantSubscriptionInvoice {
        val invoice = TenantSubscriptionInvoice.create(
            tenantId = tenantId,
            subscriptionId = subscriptionId,
            number = number,
            periodStart = activeUntil,
            periodEnd = LocalDate.of(2026, 11, 3),
            amount = BigDecimal("100000.00"),
            dueDate = activeUntil.plusDays(7),
        )
        when (status) {
            SubscriptionInvoiceStatus.ISSUED -> Unit
            SubscriptionInvoiceStatus.PAID -> invoice.markPaid(Instant.parse("2026-10-05T00:00:00Z"))
            SubscriptionInvoiceStatus.OVERDUE -> invoice.markOverdue()
            SubscriptionInvoiceStatus.VOID -> invoice.void()
        }
        return invoice
    }

    // --- fakes ---

    private class FakeSubscriptionRepository : TenantSubscriptionRepository {
        private val byId = mutableMapOf<UUID, TenantSubscription>()
        override fun findByTenantId(tenantId: UUID): TenantSubscription? = byId.values.firstOrNull { it.tenantId == tenantId }
        override fun findById(id: UUID): TenantSubscription? = byId[id]
        override fun save(subscription: TenantSubscription): TenantSubscription = subscription.also { byId[it.id] = it }
        override fun findDueForInvoice(onOrBefore: LocalDate): List<TenantSubscription> = emptyList()
    }

    private class FakeInvoiceRepository : TenantSubscriptionInvoiceRepository {
        private val byId = linkedMapOf<UUID, TenantSubscriptionInvoice>()
        fun all(): List<TenantSubscriptionInvoice> = byId.values.toList()
        override fun findById(id: UUID): TenantSubscriptionInvoice? = byId[id]
        override fun findByNumber(number: String): TenantSubscriptionInvoice? = byId.values.firstOrNull { it.number == number }
        override fun findBySubscriptionId(subscriptionId: UUID): List<TenantSubscriptionInvoice> =
            byId.values.filter { it.subscriptionId == subscriptionId }
        override fun findOutstandingBySubscriptionId(subscriptionId: UUID): List<TenantSubscriptionInvoice> =
            byId.values.filter { it.subscriptionId == subscriptionId && it.isOutstanding }
        override fun save(invoice: TenantSubscriptionInvoice): TenantSubscriptionInvoice = invoice.also { byId[it.id] = it }
    }

    private class FakePaymentRepository : TenantSubscriptionPaymentRepository {
        private val byId = linkedMapOf<UUID, TenantSubscriptionPayment>()
        override fun save(payment: TenantSubscriptionPayment): TenantSubscriptionPayment = payment.also { byId[it.id] = it }
        override fun findByInvoiceId(invoiceId: UUID): List<TenantSubscriptionPayment> =
            byId.values.filter { it.invoiceId == invoiceId }
    }

    /**
     * Service langganan sisi tenant dengan [gen] sebagai penerbit/charger. Master Pivot sengaja
     * NONAKTIF ([FakePivotRepository]) → mode sandbox mati, jadi `simulatable` pada proyeksi tagihan
     * selalu false kecuali sebuah tes memang menguji jalur simulasi.
     */
    private fun selfService(gen: PlatformInvoiceGenerator) = TenantSelfSubscriptionService(
        subscriptions, invoices, gen, FakeUsageProbe(), PivotMasterConfigProvider(FakePivotRepository()),
    )

    private class FakePlatformSettingRepository : PlatformSettingRepository {
        override fun find(): PlatformSetting? = null
        override fun save(setting: PlatformSetting): PlatformSetting = setting
    }

    private class FakePivotRepository : PivotMasterConfigRepository {
        override fun find(): PivotMasterConfig? = null
        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    /** Master Pivot AKTIF + kredensial lengkap → `resolveActive()` menghasilkan konteks charge. */
    private class EnabledPivotRepository : PivotMasterConfigRepository {
        private val config = PivotMasterConfig.rehydrate(
            id = UuidV7.generate(),
            enabled = true,
            merchantId = "merchant_id",
            merchantSecret = "merchant_secret",
            callbackApiKey = "cb_key",
            sandbox = true,
            platformFeeMinor = 0,
            platformFeeType = PivotFeeType.FIXED,
            payoutFeeMinor = 0,
            payoutFeeType = PivotFeeType.FIXED,
            payoutChannelCode = null,
            payoutAccountNumber = null,
            subAccountDefaults = SubAccountDefaults.empty(),
        )
        override fun find(): PivotMasterConfig = config
        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    /**
     * Gateway PIVOT tiruan mode-API (tanpa HTTP): mengembalikan instruksi bayar in-app menurut
     * [ChargeRequest.method] — string QRIS untuk `QR`, nomor VA + bank untuk `VIRTUAL_ACCOUNT`.
     */
    private class FakePivotGateway : PaymentGateway {
        override val provider = "PIVOT"
        /** Email pada charge terakhir — untuk memastikan tenant email diteruskan (bukan null). */
        var lastEmail: String? = null
            private set
        override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
            lastEmail = request.customerEmail
            return when (request.method) {
                "QR" -> ChargeResult(
                    provider = "PIVOT", gatewayRef = "ref_${request.invoiceNumber}", payUrl = null,
                    status = "WAITING_FOR_USER_ACTION", method = "QR",
                    qr = QrInstruction(content = "QRIS-${request.invoiceNumber}", url = null, expiresAt = null),
                )
                "VIRTUAL_ACCOUNT" -> ChargeResult(
                    provider = "PIVOT", gatewayRef = "ref_${request.invoiceNumber}", payUrl = null,
                    status = "WAITING_FOR_USER_ACTION", method = "VIRTUAL_ACCOUNT",
                    virtualAccount = VaInstruction(
                        channel = request.vaChannel, number = "8808${request.invoiceNumber}",
                        name = "Tenant Uji", expiresAt = null,
                    ),
                )
                else -> ChargeResult(provider = "PIVOT", gatewayRef = "ref_${request.invoiceNumber}", payUrl = null)
            }
        }
        override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? = null
    }

    private class FakeIamApi(private val email: String?) : IamApi {
        override fun findUser(id: UUID): UserRef? = null
        override fun usersByIds(ids: Set<UUID>): List<UserRef> = emptyList()
        override fun primaryEmailForTenant(tenantId: UUID): String? = email
    }

    private class FakeUsageProbe : SubscriptionUsageProbe {
        override fun currentTenantUsage(): List<UsageCount> = emptyList()
    }

    private class FakeTenantApi : TenantApi {
        private val platformId = UuidV7.generate()
        override fun platformTenantId(): UUID = platformId
        override fun findById(id: UUID): TenantRef? = null
        override fun findBySlug(slug: String): TenantRef? = null
        override fun requireById(id: UUID): TenantRef = TenantRef(id, "tenant-uji", "Tenant Uji", TenantStatus.ACTIVE)
        override fun findActiveTenantIds(): List<UUID> = emptyList()
        override fun ensureTenant(slug: String, name: String): TenantRef = throw NotImplementedError()
        override fun suspend(id: UUID): TenantRef = throw NotImplementedError()
        override fun activate(id: UUID): TenantRef = throw NotImplementedError()
    }

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser? = null
    }
}
