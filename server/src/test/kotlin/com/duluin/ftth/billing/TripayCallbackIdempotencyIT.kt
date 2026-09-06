package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.persistence.InvoicePersistenceAdapter
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TripayCallbackIdempotencyIT.IdempotencyTestConfiguration::class)
class TripayCallbackIdempotencyIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var invoices: InvoiceRepository
    @Autowired private lateinit var payments: PaymentRepository
    @Autowired private lateinit var gatewaySettings: TenantPaymentGatewayRepository
    @Autowired private lateinit var coordinatingInvoices: CoordinatingInvoiceRepository

    @AfterEach
    fun clearTenantContext() = TenantContext.clear()

    @Test
    fun `concurrent duplicate raw signed PAID callbacks create exactly one durable payment`() {
        val fixture = fixture()
        val rawBody = callbackBody(fixture.invoice.number)
        val signature = sign(rawBody, fixture.privateKey)
        coordinatingInvoices.arm(fixture.invoice.number)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val deliveries = List(2) {
                executor.submit {
                    mockMvc.perform(callback(rawBody, signature))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.success").value(true))
                        .andReturn()
                }
            }

            assertThat(coordinatingInvoices.awaitBothSettlementAttempts()).isTrue()
            coordinatingInvoices.releaseSettlementAttempts()
            deliveries.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            coordinatingInvoices.releaseSettlementAttempts()
            executor.shutdown()
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        TenantContext.runAs(fixture.tenantId) {
            assertThat(invoices.findById(fixture.invoice.id)?.status).isEqualTo(InvoiceStatus.PAID)
            val paymentsForInvoice = payments.findByInvoiceId(fixture.invoice.id)
            assertThat(paymentsForInvoice).hasSize(1)
            val payment = paymentsForInvoice.single()
            assertThat(payment.provider).isEqualTo("TRIPAY")
            assertThat(payment.gatewayRef).isEqualTo(fixture.gatewayRef)
            assertThat(payment.amount).isEqualByComparingTo(fixture.invoice.amount)
        }
    }

    private fun fixture(): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val tenantId = tenants.ensureTenant("tripay-idempotency-${suffix}", "Tripay Idempotency ${suffix}").id
        val privateKey = "test-only-tripay-private-key-${suffix}"
        val invoice = TenantContext.runAs(tenantId) {
            gatewaySettings.save(
                TenantPaymentGateway.defaultFor(tenantId).apply {
                    update(
                        provider = PaymentProvider.TRIPAY,
                        enabled = true,
                        tripay = TripayPaymentConfig(
                            merchantCode = "merchant-${suffix}",
                            apiKey = "test-only-tripay-api-key-${suffix}",
                            privateKey = privateKey,
                        ),
                    )
                },
            )
            invoices.save(
                Invoice.create(
                    tenantId = tenantId,
                    customerId = UUID.randomUUID(),
                    subscriptionId = UUID.randomUUID(),
                    number = "INV-TRIPAY-${suffix}",
                    periodStart = LocalDate.of(2026, 9, 1),
                    periodEnd = LocalDate.of(2026, 9, 30),
                    baseAmount = BigDecimal("150000.00"),
                    dueDate = LocalDate.of(2026, 9, 15),
                ),
            )
        }
        return Fixture(tenantId, invoice, privateKey, "TREF-${suffix}")
    }

    private fun callbackBody(invoiceNumber: String): ByteArray =
        """{"reference":"TREF-${invoiceNumber.removePrefix("INV-TRIPAY-")}","merchant_ref":"${invoiceNumber}","amount_received":150000,"status":"PAID","paid_at":1788220800}"""
            .toByteArray(StandardCharsets.UTF_8)

    private fun callback(rawBody: ByteArray, signature: String) =
        post("/api/platform/tripay/callbacks/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Callback-Signature", signature)
            .content(rawBody)

    private fun sign(rawBody: ByteArray, privateKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(privateKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
    }

    private data class Fixture(
        val tenantId: UUID,
        val invoice: Invoice,
        val privateKey: String,
        val gatewayRef: String,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class IdempotencyTestConfiguration {
        @Bean
        @Primary
        fun coordinatingInvoiceRepository(delegate: InvoicePersistenceAdapter): CoordinatingInvoiceRepository =
            CoordinatingInvoiceRepository(delegate)
    }
}

class CoordinatingInvoiceRepository(
    private val delegate: InvoiceRepository,
) : InvoiceRepository {
    private val targetNumber = AtomicReference<String?>()
    private val bothSettlementAttempts = CountDownLatch(2)
    private val releaseSettlementAttempts = CountDownLatch(1)

    fun arm(invoiceNumber: String) {
        check(targetNumber.compareAndSet(null, invoiceNumber)) { "The callback race gate is already armed" }
    }

    fun awaitBothSettlementAttempts(): Boolean = bothSettlementAttempts.await(10, TimeUnit.SECONDS)

    fun releaseSettlementAttempts() {
        releaseSettlementAttempts.countDown()
    }

    override fun save(invoice: Invoice): Invoice = delegate.save(invoice)

    override fun findById(id: UUID): Invoice? = delegate.findById(id)

    override fun findAll(): List<Invoice> = delegate.findAll()

    override fun findByNumber(number: String): Invoice? = delegate.findByNumber(number)

    override fun findForSettlementByNumber(number: String): Invoice? {
        if (number == targetNumber.get()) {
            bothSettlementAttempts.countDown()
            check(releaseSettlementAttempts.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release callback race" }
        }
        return delegate.findForSettlementByNumber(number)
    }

    override fun findByCustomerId(customerId: UUID): List<Invoice> = delegate.findByCustomerId(customerId)

    override fun findByStatus(status: InvoiceStatus): List<Invoice> = delegate.findByStatus(status)

    override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate): Boolean =
        delegate.existsForPeriod(subscriptionId, periodStart)

    override fun countForPeriod(periodStart: LocalDate): Long = delegate.countForPeriod(periodStart)

    override fun findBillableOverdue(asOf: LocalDate): List<Invoice> = delegate.findBillableOverdue(asOf)

    override fun findRemindableDueSoon(from: LocalDate, to: LocalDate): List<Invoice> =
        delegate.findRemindableDueSoon(from, to)

    override fun hasOverdueForSubscription(subscriptionId: UUID): Boolean =
        delegate.hasOverdueForSubscription(subscriptionId)

    override fun findPaidBetween(from: Instant, toExclusive: Instant): List<Invoice> =
        delegate.findPaidBetween(from, toExclusive)

    override fun findIssuedBetween(from: Instant, toExclusive: Instant): List<Invoice> =
        delegate.findIssuedBetween(from, toExclusive)

    override fun findOutstanding(asOf: LocalDate): List<Invoice> = delegate.findOutstanding(asOf)

    override fun countByStatus(): Map<InvoiceStatus, Long> = delegate.countByStatus()
}
