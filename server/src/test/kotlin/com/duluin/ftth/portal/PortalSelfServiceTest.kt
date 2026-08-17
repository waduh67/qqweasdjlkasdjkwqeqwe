package com.duluin.ftth.portal

import com.duluin.ftth.billing.BillingAccountSummary
import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.CustomerInvoiceDetailRef
import com.duluin.ftth.billing.CustomerInvoiceRef
import com.duluin.ftth.billing.CustomerPaymentRef
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.cpe.CpeDeviceStatusRef
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.SubscriptionRef
import com.duluin.ftth.helpdesk.CustomerTicketDetail
import com.duluin.ftth.helpdesk.CustomerTicketView
import com.duluin.ftth.helpdesk.HelpdeskApi
import com.duluin.ftth.helpdesk.SubmitTicketCommand
import com.duluin.ftth.portal.application.port.inbound.PortalPlanChangeCommand
import com.duluin.ftth.portal.application.service.PortalSelfService
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Menguji perakitan model-baca portal: langganan diperkaya detail paket dari katalog
 * (dan tetap tampil bila paket sudah hilang), tagihan terbuka ditandai `payable` sementara
 * tagihan lunas menyembunyikan `payUrl`, dan sesi/perangkat dipetakan apa adanya. Fake murni,
 * tanpa DB — persis semangat uji `ReportService`/`Subscriber360Service`.
 */
class PortalSelfServiceTest {

    private val customerId = UuidV7.generate()
    private val planId = UuidV7.generate()
    private val tenantId = UuidV7.generate()

    @Test
    fun `profile memperkaya langganan dengan detail paket dari katalog`() {
        val service = service(
            customer = StubCustomerApi(customerRef()).apply {
                seedSubscription(customerId, subscription(planId = planId))
            },
            catalog = FakeCatalogApi(
                commercial = mapOf(planId to commercial(planId, fee = "150000")),
                network = mapOf(planId to network(planId, down = 100, up = 50, fup = true, quota = 300_000)),
            ),
        )

        val view = service.profile(customerId)

        assertThat(view.customerId).isEqualTo(customerId)
        assertThat(view.code).isEqualTo("C-001")
        val sub = checkNotNull(view.subscription)
        assertThat(sub.monthlyFee).isEqualByComparingTo("150000")
        assertThat(sub.downMbps).isEqualTo(100)
        assertThat(sub.upMbps).isEqualTo(50)
        assertThat(sub.fupEnabled).isTrue()
        assertThat(sub.fupQuotaMb).isEqualTo(300_000)
    }

    @Test
    fun `profile langganan tanpa paket tetap tampil tanpa detail harga`() {
        val service = service(
            customer = StubCustomerApi(customerRef()).apply {
                seedSubscription(customerId, subscription(planId = null))
            },
            catalog = FakeCatalogApi(),
        )

        val sub = checkNotNull(service.profile(customerId).subscription)

        assertThat(sub.packageName).isEqualTo("Home 100")
        assertThat(sub.monthlyFee).isNull()
        assertThat(sub.downMbps).isNull()
        assertThat(sub.fupEnabled).isFalse()
        assertThat(sub.fupQuotaMb).isNull()
    }

    @Test
    fun `profile pelanggan tak ditemukan melempar NotFound`() {
        val service = service(customer = StubCustomerApi())

        assertThatThrownBy { service.profile(customerId) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `billing menandai tagihan terbuka payable dan menyembunyikan payUrl tagihan lunas`() {
        val service = service(
            billing = FakeBillingApi(
                summary = summary(outstanding = "175000"),
                invoices = listOf(
                    invoice(status = "ISSUED", payUrl = "https://pay/1"),   // terbuka → payable (metode dipilih di app)
                    invoice(status = "OVERDUE", payUrl = null),             // terbuka tanpa tautan → tetap payable
                    invoice(status = "PAID", payUrl = "https://pay/3"),     // lunas → sembunyikan tautan
                ),
                payments = listOf(payment(amount = "100000")),
            ),
        )

        val view = service.billing(customerId)

        assertThat(view.outstandingAmount).isEqualByComparingTo("175000")
        val (issued, overdue, paid) = view.invoices
        // Charge on-demand: tagihan terbuka payable tanpa syarat tautan; instrumen dipilih lewat "Bayar".
        assertThat(issued.payable).isTrue()
        assertThat(issued.payUrl).isEqualTo("https://pay/1")
        assertThat(overdue.payable).isTrue()
        assertThat(overdue.payUrl).isNull()
        assertThat(paid.payable).isFalse()
        assertThat(paid.payUrl).isNull() // tautan disembunyikan untuk tagihan lunas
        assertThat(view.payments).singleElement()
        assertThat(view.payments.first().amount).isEqualByComparingTo("100000")
    }

    @Test
    fun `connection memetakan sesi dan perangkat`() {
        val service = service(
            bng = FakeBngApi(session = session(online = true)),
            cpe = FakeCpeApi(listOf(device(online = true))),
        )

        val view = service.connection(customerId)

        assertThat(view.session).isNotNull()
        assertThat(view.session!!.online).isTrue()
        assertThat(view.session!!.username).isEqualTo("budi")
        assertThat(view.devices).singleElement()
        assertThat(view.devices.first().serialNumber).isEqualTo("SN-CPE")
    }

    @Test
    fun `connection tanpa sesi mengembalikan null tetapi tetap daftar perangkat`() {
        val service = service(
            bng = FakeBngApi(session = null),
            cpe = FakeCpeApi(listOf(device(online = false))),
        )

        val view = service.connection(customerId)

        assertThat(view.session).isNull()
        assertThat(view.devices).singleElement()
    }

    @Test
    fun `riwayat pembayaran menyebut nomor tagihan yang dilunasi`() {
        val inv = invoice(status = "PAID", payUrl = null)
        val service = service(
            billing = FakeBillingApi(
                summary = summary(),
                invoices = listOf(inv),
                payments = listOf(
                    payment(amount = "100000").copy(invoiceId = inv.id),
                    payment(amount = "90000"), // tagihannya sudah tak ada di daftar → tanpa nomor
                ),
            ),
        )

        val payments = service.billing(customerId).payments

        assertThat(payments.first().invoiceNumber).isEqualTo("INV-1")
        assertThat(payments.last().invoiceNumber).isNull()
    }

    @Test
    fun `lembar cetak membawa penerbit, penerima, paket, dan rincian pajak`() {
        val inv = invoice(status = "PAID", payUrl = null)
        val sub = subscription(planId = planId)
        val service = service(
            customer = StubCustomerApi(customerRef()).apply { seedSubscription(customerId, sub) },
            billing = FakeBillingApi(
                summary = summary(),
                detail = detail(inv, sub.id, base = "90090", tax = "9910", rate = "0.1100"),
            ),
            tenant = FakeTenantApi(name = "ISP Nusantara"),
        )

        val sheet = TenantContext.runAs(tenantId) { service.invoiceForPrint(customerId, inv.id) }

        assertThat(sheet.issuerName).isEqualTo("ISP Nusantara")
        assertThat(sheet.customerName).isEqualTo("Budi")
        assertThat(sheet.customerCode).isEqualTo("C-001")
        assertThat(sheet.packageName).isEqualTo("Home 100")
        assertThat(sheet.baseAmount).isEqualByComparingTo("90090")
        assertThat(sheet.taxAmount).isEqualByComparingTo("9910")
        assertThat(sheet.taxRate).isEqualByComparingTo("0.1100")
        // Pembayaran atas tagihan ini selalu bernomor — nomornya diketahui dari tagihan yang dicetak.
        assertThat(sheet.payments.single().invoiceNumber).isEqualTo("INV-1")
    }

    @Test
    fun `lembar cetak tagihan milik orang lain dijawab tidak ditemukan`() {
        val service = service(billing = FakeBillingApi(summary())) // fake tak punya detail apa pun

        assertThatThrownBy { TenantContext.runAs(tenantId) { service.invoiceForPrint(customerId, UuidV7.generate()) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `pilihan paket hanya yang aktif dan menandai paket yang sedang dipakai`() {
        val lainId = UuidV7.generate()
        val pensiunId = UuidV7.generate()
        val service = service(
            customer = StubCustomerApi(customerRef()).apply {
                seedSubscription(customerId, subscription(planId = planId))
            },
            catalog = FakeCatalogApi(
                commercial = mapOf(
                    planId to commercial(planId, fee = "150000"),
                    lainId to commercial(lainId, fee = "250000").copy(packageName = "Home 200"),
                    pensiunId to commercial(pensiunId, fee = "99000").copy(packageName = "Lawas", active = false),
                ),
                network = mapOf(lainId to network(lainId, down = 200, up = 100, fup = false, quota = 0)),
            ),
        )

        val options = service.planOptions(customerId)

        assertThat(options.map { it.name }).containsExactly("Home 100", "Home 200")
        assertThat(options.single { it.planId == planId }.current).isTrue()
        val lain = options.single { it.planId == lainId }
        assertThat(lain.current).isFalse()
        assertThat(lain.downMbps).isEqualTo(200)
    }

    @Test
    fun `ajuan ganti paket jadi tiket berkategori GANTI_PAKET berisi paket asal dan tujuan`() {
        val targetId = UuidV7.generate()
        val sub = subscription(planId = planId)
        val helpdesk = RecordingHelpdeskApi()
        val service = service(
            customer = StubCustomerApi(customerRef()).apply { seedSubscription(customerId, sub) },
            catalog = FakeCatalogApi(
                commercial = mapOf(
                    planId to commercial(planId, fee = "150000"),
                    targetId to commercial(targetId, fee = "250000").copy(packageName = "Home 200"),
                ),
            ),
            helpdesk = helpdesk,
        )

        val receipt = service.requestPlanChange(
            customerId,
            PortalPlanChangeCommand(sub.id, targetId, note = "  mau lebih kencang buat WFH  "),
        )

        val submitted = helpdesk.submitted.single()
        assertThat(submitted.category).isEqualTo("GANTI_PAKET")
        assertThat(submitted.subject).isEqualTo("Ajuan ganti paket: Home 100 → Home 200")
        assertThat(submitted.description)
            .contains("Paket sekarang: Home 100")
            .contains("Paket diminta: Home 200 (Rp250000/bulan)")
            .contains("mau lebih kencang buat WFH")
        assertThat(receipt.ticketCode).isEqualTo("TIK-001")
        assertThat(receipt.status).isEqualTo("OPEN")
    }

    @Test
    fun `ajuan ditolak bila langganan bukan miliknya, paket tak aktif, atau paketnya itu-itu juga`() {
        val targetId = UuidV7.generate()
        val pensiunId = UuidV7.generate()
        val sub = subscription(planId = planId)
        val service = service(
            customer = StubCustomerApi(customerRef()).apply { seedSubscription(customerId, sub) },
            catalog = FakeCatalogApi(
                commercial = mapOf(
                    planId to commercial(planId, fee = "150000"),
                    targetId to commercial(targetId, fee = "250000"),
                    pensiunId to commercial(pensiunId, fee = "99000").copy(active = false),
                ),
            ),
        )

        assertThatThrownBy {
            service.requestPlanChange(customerId, PortalPlanChangeCommand(UuidV7.generate(), targetId, null))
        }.isInstanceOf(NotFoundException::class.java)

        assertThatThrownBy {
            service.requestPlanChange(customerId, PortalPlanChangeCommand(sub.id, pensiunId, null))
        }.isInstanceOf(ValidationException::class.java)

        assertThatThrownBy {
            service.requestPlanChange(customerId, PortalPlanChangeCommand(sub.id, planId, null))
        }.isInstanceOf(ValidationException::class.java)
    }

    // --- Perkakas uji ---

    private fun service(
        customer: StubCustomerApi = StubCustomerApi(customerRef()),
        catalog: CatalogApi = FakeCatalogApi(),
        billing: BillingApi = FakeBillingApi(summary()),
        bng: BngApi = FakeBngApi(session = null),
        cpe: CpeApi = FakeCpeApi(emptyList()),
        helpdesk: HelpdeskApi = RecordingHelpdeskApi(),
        tenant: TenantApi = FakeTenantApi(),
    ) = PortalSelfService(customer, catalog, billing, bng, cpe, helpdesk, tenant)

    private fun customerRef() = CustomerRef(customerId, "C-001", "Budi", "0812", null, Coordinate(106.8, -6.2), "ACTIVE")

    private fun subscription(planId: UUID?) =
        SubscriptionRef(UuidV7.generate(), customerId, planId, "Home 100", 100, "ACTIVE")

    private fun commercial(planId: UUID, fee: String) = PlanCommercialRef(
        planId = planId, packageName = "Home 100", monthlyFee = BigDecimal(fee), bandwidthMbps = 100,
        active = true, prorateOnActivation = null, billingDayOfMonth = null,
        dueDays = null, graceDays = null, autoIsolir = null,
    )

    private fun network(planId: UUID, down: Int, up: Int, fup: Boolean, quota: Long) = PlanNetworkRef(
        planId = planId, name = "Home 100", downMbps = down, upMbps = up, rateLimit = "100M/50M",
        connectionLimit = null, fupEnabled = fup, fupQuotaMb = quota, fupRateLimit = null,
        fupDownMbps = null, fupUpMbps = null, serviceTypes = setOf("PPPOE"),
    )

    private fun summary(outstanding: String = "0") = BillingAccountSummary(
        customerId = customerId, outstandingAmount = BigDecimal(outstanding),
        outstandingCount = 1, unpaidCount = 1, oldestDueDate = null, lastPaidAt = null,
    )

    private fun invoice(status: String, payUrl: String?) = CustomerInvoiceRef(
        id = UuidV7.generate(), number = "INV-1", periodStart = LocalDate.of(2026, 8, 1),
        periodEnd = LocalDate.of(2026, 8, 31), amount = BigDecimal("100000"), status = status,
        issuedAt = Instant.parse("2026-08-01T00:00:00Z"), dueDate = LocalDate.of(2026, 8, 20),
        paidAt = null, gatewayProvider = "xendit", payUrl = payUrl,
    )

    private fun detail(inv: CustomerInvoiceRef, subscriptionId: UUID, base: String, tax: String, rate: String) =
        CustomerInvoiceDetailRef(
            invoice = inv,
            subscriptionId = subscriptionId,
            baseAmount = BigDecimal(base),
            taxAmount = BigDecimal(tax),
            taxRate = BigDecimal(rate),
            prorated = false,
            proratedDays = null,
            payments = listOf(payment(amount = "100000").copy(invoiceId = inv.id)),
        )

    private fun payment(amount: String) = CustomerPaymentRef(
        id = UuidV7.generate(), invoiceId = UuidV7.generate(), amount = BigDecimal(amount),
        provider = "xendit", paidAt = Instant.parse("2026-08-05T00:00:00Z"), note = null,
    )

    private fun session(online: Boolean) = SubscriberSessionRef(
        subscriberAccessId = UuidV7.generate(), username = "budi", accessStatus = "ACTIVE",
        rateProfileName = "Home 100", online = online, framedIp = "100.64.0.5",
        nasId = null, nasName = "BRAS-1", nasIp = null, uptimeSeconds = 120,
        startedAt = null, lastSeenAt = null,
    )

    private fun device(online: Boolean) = CpeDeviceStatusRef(
        UuidV7.generate(), "SN-CPE", "Huawei", "HG8145", "V1", "10.0.0.1", null, online,
    )

    private class FakeCatalogApi(
        private val commercial: Map<UUID, PlanCommercialRef> = emptyMap(),
        private val network: Map<UUID, PlanNetworkRef> = emptyMap(),
    ) : CatalogApi {
        override fun findPlanCommercial(planId: UUID) = commercial[planId]
        override fun findPlanNetwork(planId: UUID) = network[planId]
        override fun findPlanByName(name: String) = throw UnsupportedOperationException()
        override fun findActivePlans() = commercial.values.filter { it.active }
    }

    private class FakeBillingApi(
        private val summary: BillingAccountSummary,
        private val invoices: List<CustomerInvoiceRef> = emptyList(),
        private val payments: List<CustomerPaymentRef> = emptyList(),
        private val detail: CustomerInvoiceDetailRef? = null,
    ) : BillingApi {
        override fun findAccountSummary(customerId: UUID) = summary
        override fun findCustomerInvoices(customerId: UUID) = invoices
        override fun findCustomerPayments(customerId: UUID) = payments
        override fun findCustomerInvoiceDetail(customerId: UUID, invoiceId: UUID) =
            detail?.takeIf { it.invoice.id == invoiceId }
        override fun paymentMethods() = com.duluin.ftth.billing.PaymentMethodCatalog.methods
        override fun payCustomerInvoice(customerId: UUID, invoiceId: UUID, method: String, channel: String?) =
            throw UnsupportedOperationException()
        override fun financialReport(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
        override fun monthlyRevenue(fromMonth: java.time.YearMonth, toMonth: java.time.YearMonth) =
            throw UnsupportedOperationException()
        override fun findInvoiceForPublicLink(invoiceId: UUID) = throw UnsupportedOperationException()
        override fun payInvoiceForPublicLink(invoiceId: UUID, method: String, channel: String?) =
            throw UnsupportedOperationException()
        override fun receivableAging(asOf: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun revenueBySubscription(from: java.time.LocalDate, to: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun manualQrisImage() = throw UnsupportedOperationException()
    }

    private class FakeBngApi(private val session: SubscriberSessionRef?) : BngApi {
        override fun findPppoeByCustomerIds(customerIds: Set<UUID>): Map<UUID, com.duluin.ftth.bng.SubscriberPppoeRef> =
            throw UnsupportedOperationException()

        override fun findSubscriberSession(customerId: UUID): SubscriberSessionRef? = session
        override fun provisionAccess(command: ProvisionAccessSpec) = throw UnsupportedOperationException()
        override fun resolveNasForArea(areaId: UUID): UUID? = throw UnsupportedOperationException()
        override fun fetchPppSecretsFromNas(nasId: UUID) = throw UnsupportedOperationException()
        override fun activeSubscriberLiveness() = throw UnsupportedOperationException()
        override fun resolveNasByName(name: String) = throw UnsupportedOperationException()
        override fun findAccessByUsername(username: String) = throw UnsupportedOperationException()
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) = throw UnsupportedOperationException()
        override fun exportAccesses(): List<com.duluin.ftth.bng.AccessExportRef> = throw UnsupportedOperationException()
    }

    private class FakeCpeApi(private val devices: List<CpeDeviceStatusRef>) : CpeApi {
        override fun findDevicesForCustomer(customerId: UUID) = devices
    }

    /** Mencatat ajuan yang dikirim ke helpdesk — yang diuji adalah ISI tiketnya, bukan penyimpanannya. */
    private class RecordingHelpdeskApi : HelpdeskApi {
        val submitted = mutableListOf<SubmitTicketCommand>()

        override fun submit(command: SubmitTicketCommand): CustomerTicketDetail {
            submitted += command
            val ticket = CustomerTicketView(
                id = UuidV7.generate(), code = "TIK-001", category = command.category,
                subject = command.subject, status = "OPEN", workOrderCode = null,
                openedAt = Instant.parse("2026-08-10T00:00:00Z"),
                lastActivityAt = Instant.parse("2026-08-10T00:00:00Z"),
            )
            return CustomerTicketDetail(ticket, command.description, emptyList())
        }

        override fun findTicketsOf(customerId: UUID) = throw UnsupportedOperationException()
        override fun findTicketOf(customerId: UUID, ticketId: UUID) = throw UnsupportedOperationException()
        override fun replyAsCustomer(customerId: UUID, ticketId: UUID, body: String) = throw UnsupportedOperationException()
        override fun closeAsCustomer(customerId: UUID, ticketId: UUID) = throw UnsupportedOperationException()
    }

    private inner class FakeTenantApi(private val name: String = "ISP Demo") : TenantApi {
        override fun findById(id: UUID) = TenantRef(id, "demo", name, TenantStatus.ACTIVE)
        override fun findBySlug(slug: String) = throw UnsupportedOperationException()
        override fun requireById(id: UUID) = TenantRef(id, "demo", name, TenantStatus.ACTIVE)
        override fun platformTenantId() = throw UnsupportedOperationException()
        override fun findActiveTenantIds() = throw UnsupportedOperationException()
        override fun ensureTenant(slug: String, name: String) = throw UnsupportedOperationException()
        override fun suspend(id: UUID) = throw UnsupportedOperationException()
        override fun activate(id: UUID) = throw UnsupportedOperationException()
    }
}
