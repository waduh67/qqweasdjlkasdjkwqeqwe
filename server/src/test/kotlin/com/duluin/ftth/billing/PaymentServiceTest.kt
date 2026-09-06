package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.service.PaymentService
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.CustomerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class PaymentServiceTest {

    @Test
    fun `settlement for a missing invoice is ignored without creating a payment`() {
        val invoices = mock(InvoiceRepository::class.java)
        val payments = mock(PaymentRepository::class.java)
        val customers = mock(CustomerApi::class.java)
        val auditor = mock(AuditRecorder::class.java)
        val service = PaymentService(invoices, payments, customers, auditor)

        service.applySettlement(settlementFor(issuedInvoice()))

        verifyNoInteractions(payments, customers, auditor)
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceStatus::class, names = ["PAID", "VOID", "REFUNDED"])
    fun `settlement for a non unpaid invoice is ignored without creating a payment`(status: InvoiceStatus) {
        val invoice = invoiceWithStatus(status)
        val invoices = mock(InvoiceRepository::class.java)
        val payments = mock(PaymentRepository::class.java)
        val customers = mock(CustomerApi::class.java)
        val auditor = mock(AuditRecorder::class.java)
        `when`(invoices.findForSettlementByNumber(invoice.number)).thenReturn(invoice)
        val service = PaymentService(invoices, payments, customers, auditor)

        service.applySettlement(settlementFor(invoice))

        assertThat(invoice.status).isEqualTo(status)
        verifyNoInteractions(payments, customers, auditor)
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceStatus::class, names = ["ISSUED", "OVERDUE"])
    fun `settlement for an unpaid invoice appends a payment`(status: InvoiceStatus) {
        val invoice = invoiceWithStatus(status)
        val invoices = mock(InvoiceRepository::class.java)
        val payments = RecordingPaymentRepository()
        val customers = mock(CustomerApi::class.java)
        val auditor = mock(AuditRecorder::class.java)
        `when`(invoices.findForSettlementByNumber(invoice.number)).thenReturn(invoice)
        `when`(invoices.save(invoice)).thenReturn(invoice)
        val service = PaymentService(invoices, payments, customers, auditor)

        service.applySettlement(settlementFor(invoice))

        assertThat(invoice.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(payments.saved).hasSize(1)
    }

    private fun issuedInvoice(): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        number = "INV-202609-0001",
        periodStart = LocalDate.of(2026, 9, 1),
        periodEnd = LocalDate.of(2026, 9, 30),
        baseAmount = BigDecimal("150000.00"),
        dueDate = LocalDate.of(2026, 9, 15),
    )

    private fun invoiceWithStatus(status: InvoiceStatus): Invoice = issuedInvoice().apply {
        when (status) {
            InvoiceStatus.ISSUED -> Unit
            InvoiceStatus.OVERDUE -> markOverdue()
            InvoiceStatus.PAID -> markPaid(Instant.parse("2026-09-06T11:00:00Z"))
            InvoiceStatus.VOID -> void()
            InvoiceStatus.REFUNDED -> {
                markPaid(Instant.parse("2026-09-06T11:00:00Z"))
                applyRefund(amount)
            }
        }
    }

    private fun settlementFor(invoice: Invoice) = PaymentSettlement(
        invoiceNumber = invoice.number,
        gatewayRef = "TREF-202609-0001",
        amount = invoice.amount,
        paidAt = Instant.parse("2026-09-06T12:00:00Z"),
        provider = "TRIPAY",
    )

    private class RecordingPaymentRepository : PaymentRepository {
        val saved = mutableListOf<Payment>()

        override fun save(payment: Payment): Payment = payment.also(saved::add)

        override fun findByInvoiceId(invoiceId: java.util.UUID): List<Payment> = emptyList()

        override fun findByCustomerId(customerId: java.util.UUID): List<Payment> = emptyList()
    }
}
