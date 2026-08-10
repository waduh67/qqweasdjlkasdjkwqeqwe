package com.duluin.ftth.platformbilling

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.PublicInvoiceRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.platformbilling.application.port.inbound.PublicInvoiceView
import com.duluin.ftth.platformbilling.application.service.PublicInvoicePaymentService
import com.duluin.ftth.platformbilling.application.service.PublicSubscriptionInvoices
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Menguji orkestrator halaman bayar publik: pemilihan realm (tagihan pelanggan vs tagihan
 * langganan SaaS) dan — yang paling penting — bahwa SEMUA sebab gagal memulangkan KALIMAT
 * YANG SAMA. Tautannya dipegang siapa saja; beda pesan antara "slug asing", "UUID asing", dan
 * "tagihan tenant lain" berarti pemegang tautan bisa menebak-nebak keberadaan tagihan orang.
 *
 * Fake [BillingApi] di sini sengaja membaca [TenantContext] alih-alih parameter tenantId —
 * meniru RLS tabel `invoice`: tagihan tenant lain memang TAK TERLIHAT, bukan disaring di aplikasi.
 */
class PublicInvoicePaymentServiceTest {

    private val mynet = TenantRef(UuidV7.generate(), "mynet", "PT Mynet", TenantStatus.ACTIVE)
    private val lain = TenantRef(UuidV7.generate(), "lain", "PT Lain", TenantStatus.ACTIVE)

    @Test
    fun `slug tenant yang tak dikenal memulangkan pesan yang sama dengan tagihan tak ada`() {
        val service = service()

        val slugAsing = catchMessage { service.find("tidak-ada", UuidV7.generate()) }
        val uuidAsing = catchMessage { service.find("mynet", UuidV7.generate()) }

        assertThat(slugAsing).isEqualTo(NOT_FOUND)
        assertThat(uuidAsing).isEqualTo(NOT_FOUND)
    }

    @Test
    fun `tagihan milik tenant lain tak bisa dibuka lewat slug tenant ini`() {
        val invoiceId = UuidV7.generate()
        val service = service(customerInvoices = mapOf(lain.id to publicRef(invoiceId)))

        val pesan = catchMessage { service.find("mynet", invoiceId) }

        assertThat(pesan).isEqualTo(NOT_FOUND)
        // …sementara lewat slug pemiliknya tetap terbuka: yang menyaring memang tenant, bukan UUID.
        assertThat(service(customerInvoices = mapOf(lain.id to publicRef(invoiceId))).find("lain", invoiceId).id)
            .isEqualTo(invoiceId)
    }

    @Test
    fun `tagihan langganan SaaS ketemu lewat realm kedua dengan tenant sebagai pihak tertagih`() {
        val invoice = subscriptionInvoice(mynet.id)
        val service = service(subscriptions = FakeSubscriptions(mapOf(invoice.id to invoice)))

        val view = service.find("mynet", invoice.id)

        assertThat(view.payerName).isEqualTo("PT Mynet")
        assertThat(view.tenantSlug).isEqualTo("mynet")
        assertThat(view.status).isEqualTo("ISSUED")
        // Langganan SaaS selalu lewat akun master Pivot — tak ada jalur transfer manual.
        assertThat(view.manual).isNull()
    }

    @Test
    fun `tagihan langganan milik tenant lain juga memulangkan pesan yang sama`() {
        val invoice = subscriptionInvoice(lain.id)
        val service = service(subscriptions = FakeSubscriptions(mapOf(invoice.id to invoice)))

        assertThat(catchMessage { service.find("mynet", invoice.id) }).isEqualTo(NOT_FOUND)
    }

    @Test
    fun `pay memilih realm dari keberadaan tagihannya, bukan dari parameter klien`() {
        val subscription = subscriptionInvoice(mynet.id)
        val subscriptions = FakeSubscriptions(mapOf(subscription.id to subscription))
        val customerInvoiceId = UuidV7.generate()
        val billing = FakeBilling(mapOf(mynet.id to publicRef(customerInvoiceId)))
        val service = PublicInvoicePaymentService(FakeTenants(), billing, subscriptions)

        service.pay("mynet", customerInvoiceId, "QR", null)
        service.pay("mynet", subscription.id, "QR", null)

        assertThat(billing.paidIds).containsExactly(customerInvoiceId)
        assertThat(subscriptions.paidIds).containsExactly(subscription.id)
    }

    @Test
    fun `pay atas tagihan yang tak ada di kedua realm memulangkan pesan yang sama`() {
        val service = service()

        assertThat(catchMessage { service.pay("mynet", UuidV7.generate(), "QR", null) }).isEqualTo(NOT_FOUND)
    }

    @Test
    fun `gambar QRIS manual hanya bisa diambil lewat tautan tagihan yang sahih`() {
        val invoiceId = UuidV7.generate()
        val service = service(customerInvoices = mapOf(mynet.id to publicRef(invoiceId)))

        assertThat(service.manualQrisImage("mynet", invoiceId)?.contentType).isEqualTo("image/png")
        // Slug saja tak cukup: tanpa UUID tagihan yang benar, gambarnya tak bisa dipanen.
        assertThat(catchMessage { service.manualQrisImage("mynet", UuidV7.generate()) }).isEqualTo(NOT_FOUND)
    }

    @Test
    fun `proyeksi halaman publik tak punya tempat untuk referensi gateway`() {
        assertThat(PublicInvoiceView::class.java.declaredFields.map { it.name })
            .doesNotContain("gatewayRef", "paymentSessionId", "payUrl", "simulatable")
    }

    @Test
    fun `metode bayar diteruskan apa adanya dari katalog billing`() {
        assertThat(service().paymentMethods()).isEqualTo(PaymentMethodCatalog.methods)
    }

    // --- Perkakas uji ---

    private fun service(
        customerInvoices: Map<UUID, PublicInvoiceRef> = emptyMap(),
        subscriptions: PublicSubscriptionInvoices = FakeSubscriptions(emptyMap()),
    ) = PublicInvoicePaymentService(FakeTenants(), FakeBilling(customerInvoices), subscriptions)

    private fun catchMessage(block: () -> Unit): String? =
        runCatching { block() }.exceptionOrNull()
            ?.also { assertThat(it).isInstanceOf(NotFoundException::class.java) }
            ?.message

    private fun publicRef(id: UUID) = PublicInvoiceRef(
        id = id,
        number = "INV-2026-08-0001",
        customerName = "Budi",
        periodStart = LocalDate.of(2026, 8, 1),
        periodEnd = LocalDate.of(2026, 8, 31),
        amount = BigDecimal("150000.00"),
        status = "ISSUED",
        dueDate = LocalDate.of(2026, 8, 10),
        paidAt = null,
        payableOnline = true,
    )

    private fun subscriptionInvoice(tenantId: UUID) = TenantSubscriptionInvoice.create(
        tenantId = tenantId,
        subscriptionId = UuidV7.generate(),
        number = "SUB-2026-08-0001",
        periodStart = LocalDate.of(2026, 8, 1),
        periodEnd = LocalDate.of(2026, 8, 31),
        amount = BigDecimal("299000.00"),
        dueDate = LocalDate.of(2026, 8, 10),
    )

    private inner class FakeTenants : TenantApi {
        override fun findBySlug(slug: String): TenantRef? = listOf(mynet, lain).firstOrNull { it.slug == slug }
        override fun findById(id: UUID): TenantRef? = listOf(mynet, lain).firstOrNull { it.id == id }

        override fun requireById(id: UUID) = throw UnsupportedOperationException()
        override fun platformTenantId() = throw UnsupportedOperationException()
        override fun findActiveTenantIds() = throw UnsupportedOperationException()
        override fun ensureTenant(slug: String, name: String) = throw UnsupportedOperationException()
        override fun suspend(id: UUID) = throw UnsupportedOperationException()
        override fun activate(id: UUID) = throw UnsupportedOperationException()
    }

    /** Tagihan pelanggan per tenant; yang terlihat hanya milik tenant di [TenantContext] (cermin RLS). */
    private class FakeBilling(private val byTenant: Map<UUID, PublicInvoiceRef>) : BillingApi {
        val paidIds = mutableListOf<UUID>()

        override fun findInvoiceForPublicLink(invoiceId: UUID): PublicInvoiceRef? =
            byTenant[TenantContext.tenantId()]?.takeIf { it.id == invoiceId }

        override fun payInvoiceForPublicLink(invoiceId: UUID, method: String, channel: String?): PublicInvoiceRef {
            paidIds += invoiceId
            return findInvoiceForPublicLink(invoiceId) ?: throw NotFoundException("Tagihan tidak ditemukan")
        }

        override fun receivableAging(asOf: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun revenueBySubscription(from: java.time.LocalDate, to: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun manualQrisImage() = StoredObject("image/png", ByteArray(4))
        override fun paymentMethods() = PaymentMethodCatalog.methods

        override fun findAccountSummary(customerId: UUID) = throw UnsupportedOperationException()
        override fun findCustomerInvoices(customerId: UUID) = throw UnsupportedOperationException()
        override fun findCustomerPayments(customerId: UUID) = throw UnsupportedOperationException()
        override fun findCustomerInvoiceDetail(customerId: UUID, invoiceId: UUID) = throw UnsupportedOperationException()
        override fun payCustomerInvoice(customerId: UUID, invoiceId: UUID, method: String, channel: String?) =
            throw UnsupportedOperationException()

        override fun financialReport(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
        override fun monthlyRevenue(fromMonth: java.time.YearMonth, toMonth: java.time.YearMonth) =
            throw UnsupportedOperationException()
    }

    private class FakeSubscriptions(
        private val invoices: Map<UUID, TenantSubscriptionInvoice>,
    ) : PublicSubscriptionInvoices {
        val paidIds = mutableListOf<UUID>()

        override fun find(invoiceId: UUID, tenantId: UUID): TenantSubscriptionInvoice? =
            invoices[invoiceId]?.takeIf { it.tenantId == tenantId }

        override fun pay(
            invoiceId: UUID,
            tenantId: UUID,
            method: String,
            channel: String?,
        ): TenantSubscriptionInvoice {
            paidIds += invoiceId
            return find(invoiceId, tenantId) ?: throw NotFoundException("Tagihan tidak ditemukan")
        }

        override fun payableOnline(invoice: TenantSubscriptionInvoice) = true
    }

    private companion object {
        const val NOT_FOUND = "Tagihan tidak ditemukan atau tautannya sudah tidak berlaku"
    }
}
