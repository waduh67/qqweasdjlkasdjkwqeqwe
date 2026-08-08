package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.application.port.outbound.BillingTaxSettingsRepository
import com.duluin.ftth.billing.application.service.BillingCycleRunner
import com.duluin.ftth.billing.application.service.BillingTaxSettingsResolver
import com.duluin.ftth.billing.application.service.InvoiceGenerator
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.application.service.TenantPaymentGatewayResolver
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.BillingTaxSettings
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.ManualPaymentConfig
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.billing.domain.model.SubAccountDefaults
import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.billing.domain.model.SubAccountType
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.BillableSubscription
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Menguji siklus penagihan (penerbitan prorata + penegakan tunggakan) memakai fake
 * port murni — tanpa Spring/DB. Fokus perilaku yang tak tercakup uji domain [InvoiceTest]:
 * prorata mengalir konsisten ke tagihan DAN charge gateway, gating tanggal tagih per
 * langganan, serta grace/autoIsolir per-paket dengan fallback global.
 */
class BillingCycleTest {

    @AfterEach
    fun tearDown() = TenantContext.clear()

    // --- Penerbitan: prorata & konsistensi tagihan↔charge ---

    @Test
    fun `penerbitan memprorata tagihan saat aktivasi tengah periode`() {
        val sub = billable(monthlyFee = BigDecimal("310000"), prorateOnActivation = true, activatedAt = dateAt(2026, 7, 16))
        val f = fixture(props(), billables = listOf(sub))

        val count = f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 20))

        assertThat(count).isEqualTo(1)
        val invoice = f.repo.saved.single()
        assertThat(invoice.prorated).isTrue()
        assertThat(invoice.proratedDays).isEqualTo(16) // 16..31 inklusif
        assertThat(invoice.amount).isEqualByComparingTo("160000") // 310000 * 16 / 31
        // Charge on-demand: penerbitan tak membuat charge (instrumen dipilih pelanggan lewat "Bayar").
        assertThat(f.gateway.charges).isEmpty()
    }

    @Test
    fun `penerbitan menagih penuh saat aktivasi hari pertama periode`() {
        val sub = billable(monthlyFee = BigDecimal("310000"), prorateOnActivation = true, activatedAt = dateAt(2026, 7, 1))
        val f = fixture(props(), billables = listOf(sub))

        f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 20))

        val invoice = f.repo.saved.single()
        assertThat(invoice.prorated).isFalse()
        assertThat(invoice.proratedDays).isNull()
        assertThat(invoice.amount).isEqualByComparingTo("310000")
    }

    @Test
    fun `prorata global berlaku saat paket tak menyetel flag`() {
        val sub = billable(prorateOnActivation = null, activatedAt = dateAt(2026, 7, 16))
        val f = fixture(props(prorate = true), billables = listOf(sub))

        f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 20))

        assertThat(f.repo.saved.single().prorated).isTrue()
    }

    @Test
    fun `tanpa prorata aktif aktivasi tengah bulan tetap penuh`() {
        val sub = billable(monthlyFee = BigDecimal("310000"), prorateOnActivation = null, activatedAt = dateAt(2026, 7, 16))
        val f = fixture(props(prorate = false), billables = listOf(sub))

        f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 20))

        val invoice = f.repo.saved.single()
        assertThat(invoice.prorated).isFalse()
        assertThat(invoice.amount).isEqualByComparingTo("310000")
    }

    @Test
    fun `charge on-demand meneruskan email pelanggan ke gateway (Pivot mewajibkannya)`() {
        TenantContext.set(UuidV7.generate())
        val customerId = UuidV7.generate()
        val sub = billable(customerId = customerId)
        val customer = CustomerRef(customerId, "CUST-000009", "Budi", "0812", "budi@mail.test", Coordinate(0.0, 0.0), "ACTIVE")
        val f = pivotFixture(billables = listOf(sub), customers = mapOf(customerId to customer))
        f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 20))
        val invoice = f.repo.saved.single()

        f.generator.chargeWithMethod(invoice, "QR", null)

        val charge = f.gateway.charges.single()
        assertThat(charge.customerName).isEqualTo("Budi")
        assertThat(charge.customerEmail).isEqualTo("budi@mail.test")
        assertThat(charge.method).isEqualTo("QR")
    }

    @Test
    fun `charge on-demand tanpa data pelanggan memakai nomor tagihan dan email null (fail-soft)`() {
        TenantContext.set(UuidV7.generate())
        val sub = billable()
        val f = pivotFixture(billables = listOf(sub)) // tanpa CustomerRef → nama/email tak tersedia
        f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 20))
        val invoice = f.repo.saved.single()

        f.generator.chargeWithMethod(invoice, "QR", null)

        val charge = f.gateway.charges.single()
        assertThat(charge.customerName).isEqualTo(invoice.number) // fallback ke nomor tagihan
        assertThat(charge.customerEmail).isNull()
    }

    // --- Penerbitan: gating tanggal tagih per langganan ---

    @Test
    fun `langganan dgn tanggal tagih belum tiba dilewati`() {
        val sub = billable(billingDayOfMonth = 20)
        val f = fixture(props(billingDay = 1), billables = listOf(sub))

        val count = f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 10))

        assertThat(count).isEqualTo(0)
        assertThat(f.repo.saved).isEmpty()
    }

    @Test
    fun `langganan terbit setelah tanggal tagihnya tercapai`() {
        val sub = billable(billingDayOfMonth = 20)
        val f = fixture(props(billingDay = 1), billables = listOf(sub))

        val count = f.generator.generateFor(UuidV7.generate(), LocalDate.of(2026, 7, 25))

        assertThat(count).isEqualTo(1)
    }

    // --- Penegakan: grace & autoIsolir per-paket dengan fallback global ---

    @Test
    fun `penegakan menghormati grace paket - masih dalam grace tak ditandai`() {
        val subId = UuidV7.generate()
        val sub = billable(subscriptionId = subId, graceDays = 5)
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().minusDays(2))
        val f = fixture(props(grace = 1), overdue = listOf(invoice), byId = mapOf(subId to sub))

        f.runner.enforce(UuidV7.generate())

        assertThat(f.repo.saved).isEmpty()
        assertThat(f.customerApi.isolated).isEmpty()
    }

    @Test
    fun `penegakan menandai menunggak dan isolir setelah lewat grace paket`() {
        val subId = UuidV7.generate()
        val sub = billable(subscriptionId = subId, graceDays = 5, autoIsolir = true)
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().minusDays(10))
        val f = fixture(props(grace = 1), overdue = listOf(invoice), byId = mapOf(subId to sub))

        f.runner.enforce(UuidV7.generate())

        assertThat(f.repo.saved.single().status).isEqualTo(InvoiceStatus.OVERDUE)
        assertThat(f.customerApi.isolated).containsExactly(subId)
    }

    @Test
    fun `autoIsolir paket false menandai menunggak tanpa mengisolir`() {
        val subId = UuidV7.generate()
        val sub = billable(subscriptionId = subId, graceDays = 0, autoIsolir = false)
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().minusDays(5))
        val f = fixture(props(grace = 3, autoIsolir = true), overdue = listOf(invoice), byId = mapOf(subId to sub))

        f.runner.enforce(UuidV7.generate())

        assertThat(f.repo.saved.single().status).isEqualTo(InvoiceStatus.OVERDUE)
        assertThat(f.customerApi.isolated).isEmpty()
    }

    @Test
    fun `langganan tak ditemukan jatuh ke kebijakan global`() {
        val subId = UuidV7.generate() // tak ada di byId → pakai grace/autoIsolir global
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().minusDays(10))
        val f = fixture(props(grace = 3, autoIsolir = true), overdue = listOf(invoice), byId = emptyMap())

        f.runner.enforce(UuidV7.generate())

        assertThat(f.repo.saved.single().status).isEqualTo(InvoiceStatus.OVERDUE)
        assertThat(f.customerApi.isolated).containsExactly(subId)
    }

    @Test
    fun `penegakan menerbitkan event InvoiceOverdue sekali per tagihan`() {
        val subId = UuidV7.generate()
        val sub = billable(subscriptionId = subId, graceDays = 5, autoIsolir = true)
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().minusDays(10))
        val f = fixture(props(grace = 1), overdue = listOf(invoice), byId = mapOf(subId to sub))

        f.runner.enforce(UuidV7.generate())

        val overdueEvents = f.events.published.filterIsInstance<InvoiceOverdue>()
        assertThat(overdueEvents).hasSize(1)
        assertThat(overdueEvents.single().invoiceId).isEqualTo(invoice.id)
        assertThat(overdueEvents.single().customerId).isEqualTo(invoice.customerId)
    }

    // --- Penegakan: sweep pengingat jatuh tempo (due-soon) ---

    @Test
    fun `sweep due-soon menerbitkan InvoiceDueSoon menandai teringatkan dan idempoten`() {
        val subId = UuidV7.generate()
        // reminderDaysBefore bawaan = 3, jadi jatuh tempo H+2 masuk jendela.
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().plusDays(2))
        val f = fixture(props(), overdue = listOf(invoice))

        f.runner.remindDueSoon(UuidV7.generate())

        assertThat(invoice.dueSoonReminded).isTrue()
        val dueSoon = f.events.published.filterIsInstance<InvoiceDueSoon>()
        assertThat(dueSoon).hasSize(1)
        assertThat(dueSoon.single().invoiceId).isEqualTo(invoice.id)

        // Sweep kedua tak boleh mengirim ulang — flag sudah menyaringnya keluar.
        f.runner.remindDueSoon(UuidV7.generate())
        assertThat(f.events.published.filterIsInstance<InvoiceDueSoon>()).hasSize(1)
    }

    @Test
    fun `sweep due-soon melewati tagihan di luar jendela pengingat`() {
        val subId = UuidV7.generate()
        val invoice = issuedInvoice(subId, dueDate = LocalDate.now().plusDays(10)) // > 3 hari
        val f = fixture(props(), overdue = listOf(invoice))

        f.runner.remindDueSoon(UuidV7.generate())

        assertThat(invoice.dueSoonReminded).isFalse()
        assertThat(f.events.published.filterIsInstance<InvoiceDueSoon>()).isEmpty()
    }

    // --- Perkakas uji ---

    private class Fixture(
        val generator: InvoiceGenerator,
        val runner: BillingCycleRunner,
        val repo: FakeInvoiceRepository,
        val gateway: CapturingGateway,
        val customerApi: FakeCustomerApi,
        val events: CapturingEvents,
    )

    private fun fixture(
        props: BillingProperties,
        billables: List<BillableSubscription> = emptyList(),
        overdue: List<Invoice> = emptyList(),
        byId: Map<UUID, BillableSubscription> = emptyMap(),
        customers: Map<UUID, CustomerRef> = emptyMap(),
    ): Fixture {
        val repo = FakeInvoiceRepository(overdue)
        val customerApi = FakeCustomerApi(billables, byId, customers)
        val gateway = CapturingGateway()
        val registry = PaymentGatewayRegistry(listOf(gateway), props)
        // Tanpa baris config tenant → resolver jatuh ke fallback MANUAL; adapter penangkap
        // memakai provider "MANUAL" agar registry memilihnya untuk konteks itu.
        // Tanpa master Pivot & tanpa sub-account → resolver pasti jatuh ke MANUAL.
        val masterConfig = PivotMasterConfigProvider(NoMasterConfig)
        val resolver = TenantPaymentGatewayResolver(NoGatewayConfig, NoPivotAccount, masterConfig, NoTenantApi, props)
        // Tanpa baris setelan pajak → resolver jatuh ke bawaan (PPN mati) → tagihan tanpa PPN,
        // jadi assertion nilai tagihan↔charge di test ini tetap murni tarif dasar.
        val taxResolver = BillingTaxSettingsResolver(NoTaxConfig)
        val auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser)
        val generator = InvoiceGenerator(repo, customerApi, registry, resolver, taxResolver, auditor, props)
        // Publisher penangkap khusus buntut siklus (InvoiceDueSoon/InvoiceOverdue) —
        // auditor tetap no-op agar event audit tak mengotori assertion.
        val events = CapturingEvents()
        val runner = BillingCycleRunner(generator, repo, customerApi, auditor, props, events, NoTenantApi)
        return Fixture(generator, runner, repo, gateway, customerApi, events)
    }

    /**
     * Fixture dengan gateway aktif PIVOT (resolver menghasilkan konteks PIVOT), untuk menguji
     * jalur charge on-demand [InvoiceGenerator.chargeWithMethod] — MANUAL ditolak, jadi tak bisa
     * dipakai fixture default. Sub-account tenant terprovisi + master aktif = prasyarat PIVOT.
     */
    private fun pivotFixture(
        billables: List<BillableSubscription> = emptyList(),
        customers: Map<UUID, CustomerRef> = emptyMap(),
    ): Fixture {
        val props = props()
        val repo = FakeInvoiceRepository(emptyList())
        val customerApi = FakeCustomerApi(billables, emptyMap(), customers)
        val gateway = CapturingGateway("PIVOT")
        val registry = PaymentGatewayRegistry(listOf(gateway), props)
        val masterConfig = PivotMasterConfigProvider(EnabledMaster)
        val resolver = TenantPaymentGatewayResolver(PivotGatewayConfig, ProvisionedPivotAccount, masterConfig, NoTenantApi, props)
        val taxResolver = BillingTaxSettingsResolver(NoTaxConfig)
        val auditor = AuditRecorder(ApplicationEventPublisher { }, NoUser)
        val generator = InvoiceGenerator(repo, customerApi, registry, resolver, taxResolver, auditor, props)
        val events = CapturingEvents()
        val runner = BillingCycleRunner(generator, repo, customerApi, auditor, props, events, NoTenantApi)
        return Fixture(generator, runner, repo, gateway, customerApi, events)
    }

    private fun props(
        prorate: Boolean = false,
        billingDay: Int = 1,
        grace: Long = 3,
        autoIsolir: Boolean = true,
    ) = BillingProperties(
        billingDayOfMonth = billingDay,
        graceDays = grace,
        autoIsolir = autoIsolir,
        prorateOnActivation = prorate,
    )

    private fun billable(
        subscriptionId: UUID = UuidV7.generate(),
        customerId: UUID = UuidV7.generate(),
        monthlyFee: BigDecimal = BigDecimal("310000"),
        status: String = "ACTIVE",
        activatedAt: Instant? = null,
        prorateOnActivation: Boolean? = null,
        billingDayOfMonth: Int? = null,
        graceDays: Int? = null,
        autoIsolir: Boolean? = null,
    ) = BillableSubscription(
        subscriptionId = subscriptionId,
        customerId = customerId,
        packageName = "Home 100",
        monthlyFee = monthlyFee,
        status = status,
        activatedAt = activatedAt,
        prorateOnActivation = prorateOnActivation,
        billingDayOfMonth = billingDayOfMonth,
        graceDays = graceDays,
        autoIsolir = autoIsolir,
    )

    private fun issuedInvoice(subscriptionId: UUID, dueDate: LocalDate): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        subscriptionId = subscriptionId,
        number = "INV-202607-0001",
        periodStart = LocalDate.of(2026, 7, 1),
        periodEnd = LocalDate.of(2026, 7, 31),
        baseAmount = BigDecimal("100000"),
        dueDate = dueDate,
    )

    private fun dateAt(year: Int, month: Int, day: Int): Instant =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant()

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull() = null
    }

    /** Menangkap event domain yang diterbitkan runner agar bisa di-assert (InvoiceDueSoon/InvoiceOverdue). */
    private class CapturingEvents : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published.add(event)
        }
    }

    private class CapturingGateway(override val provider: String = "MANUAL") : PaymentGateway {
        val charges = mutableListOf<ChargeRequest>()

        override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
            charges.add(request)
            return ChargeResult(provider, "ref-${request.invoiceNumber}", null)
        }

        override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext) =
            throw UnsupportedOperationException()
    }

    /** Repo config gateway kosong → resolver memakai fallback MANUAL (perilaku default tenant). */
    private object NoGatewayConfig : TenantPaymentGatewayRepository {
        override fun find(): TenantPaymentGateway? = null
        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway = settings
    }

    /** Tenant memakai PIVOT & aktif → resolver menghasilkan konteks PIVOT (charge in-app). */
    private object PivotGatewayConfig : TenantPaymentGatewayRepository {
        override fun find(): TenantPaymentGateway = TenantPaymentGateway.rehydrate(
            id = UuidV7.generate(),
            tenantId = UuidV7.generate(),
            provider = PaymentProvider.PIVOT,
            enabled = true,
            manual = ManualPaymentConfig.EMPTY,
            qrisStorageKey = null,
            qrisContentType = null,
        )
        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway = settings
    }

    /** Sub-account Pivot tenant sudah terprovisi & aktif → prasyarat charge PIVOT terpenuhi. */
    private object ProvisionedPivotAccount : TenantPivotAccountRepository {
        private val account = TenantPivotAccount.rehydrate(
            id = UuidV7.generate(),
            tenantId = UuidV7.generate(),
            subMerchantUuid = "sub_merchant_uuid",
            type = SubAccountType.NON_KYC,
            status = SubAccountStatus.ACTIVE,
            kycStatus = SubAccountKycStatus.NOT_REQUIRED,
            shortName = null, legalName = null, merchantEmail = null, merchantPhone = null,
            picName = null, picEmail = null, picPhone = null, address = null,
            payoutChannelCode = null, payoutAccountNumber = null, payoutAccountName = null, payoutInquiryId = null,
        )
        override fun find(): TenantPivotAccount = account
        override fun save(account: TenantPivotAccount): TenantPivotAccount = account
        override fun findByTenant(tenantId: UUID): TenantPivotAccount = account
    }

    /** Master Pivot aktif + kredensial lengkap → `PivotMasterConfigProvider.current()` non-null. */
    private object EnabledMaster : PivotMasterConfigRepository {
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

    /** Tenant belum punya sub-account Pivot → salah satu prasyarat PIVOT tak terpenuhi. */
    private object NoPivotAccount : TenantPivotAccountRepository {
        override fun find(): TenantPivotAccount? = null
        override fun save(account: TenantPivotAccount): TenantPivotAccount = account
        override fun findByTenant(tenantId: UUID): TenantPivotAccount? = null
    }

    /** Resolver hanya menyentuh TenantApi di cabang PIVOT; test ini murni MANUAL → tak pernah dipanggil. */
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

    /** Platform belum mengaktifkan master Pivot → resolver tak bisa membentuk konteks PIVOT. */
    private object NoMasterConfig : PivotMasterConfigRepository {
        override fun find(): PivotMasterConfig? = null
        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    private object NoTaxConfig : BillingTaxSettingsRepository {
        // Kembalikan bawaan (PPN mati) langsung agar resolver tak menyentuh TenantContext —
        // test siklus ini murni fake tanpa request, jadi tak ada tenant di context.
        override fun find(): BillingTaxSettings = BillingTaxSettings.defaultFor(UuidV7.generate())
        override fun save(settings: BillingTaxSettings): BillingTaxSettings = settings
    }

    private class FakeInvoiceRepository(private val overdue: List<Invoice>) : InvoiceRepository {
        val saved = mutableListOf<Invoice>()

        override fun save(invoice: Invoice): Invoice {
            saved.add(invoice)
            return invoice
        }

        override fun findById(id: UUID): Invoice? = saved.find { it.id == id }

        override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate) = false

        override fun countForPeriod(periodStart: LocalDate) = 0L

        override fun findBillableOverdue(asOf: LocalDate): List<Invoice> =
            overdue.filter { it.status == InvoiceStatus.ISSUED && it.dueDate.isBefore(asOf) }

        override fun findRemindableDueSoon(from: LocalDate, to: LocalDate): List<Invoice> =
            overdue.filter {
                it.status == InvoiceStatus.ISSUED && !it.dueSoonReminded &&
                    !it.dueDate.isBefore(from) && !it.dueDate.isAfter(to)
            }

        override fun findAll() = throw UnsupportedOperationException()
        override fun findByNumber(number: String) = throw UnsupportedOperationException()
        override fun findByCustomerId(customerId: UUID) = throw UnsupportedOperationException()
        override fun findByStatus(status: InvoiceStatus) = throw UnsupportedOperationException()
        override fun hasOverdueForSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun findPaidBetween(from: Instant, toExclusive: Instant) = throw UnsupportedOperationException()
        override fun findIssuedBetween(from: Instant, toExclusive: Instant) = throw UnsupportedOperationException()
        override fun findOutstanding(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun countByStatus() = throw UnsupportedOperationException()
    }

    private class FakeCustomerApi(
        private val billables: List<BillableSubscription>,
        private val byId: Map<UUID, BillableSubscription>,
        private val customers: Map<UUID, CustomerRef> = emptyMap(),
    ) : CustomerApi {
        val isolated = mutableListOf<UUID>()

        override fun findBillableSubscriptions() = billables

        override fun findBillableSubscription(subscriptionId: UUID) = byId[subscriptionId]

        override fun findCustomersByIds(ids: Set<UUID>): List<CustomerRef> = ids.mapNotNull { customers[it] }

        override fun isolateForBilling(subscriptionId: UUID) {
            isolated.add(subscriptionId)
        }

        override fun findCustomer(id: UUID): CustomerRef? = customers[id]
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
        override fun provisionOnu(command: ProvisionOnuCommand) = throw UnsupportedOperationException()
        override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun registerCustomer(command: RegisterCustomerCommand) = throw UnsupportedOperationException()
        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: BigDecimal?) =
            throw UnsupportedOperationException()

        override fun subscriberStats() = throw UnsupportedOperationException()
        override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) = throw UnsupportedOperationException()
        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: java.time.Instant?, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<java.util.UUID>): List<com.duluin.ftth.customer.CustomerExportRow> = throw UnsupportedOperationException()
    }
}
