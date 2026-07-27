package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Menguji alur pelunasan pada tingkat domain: bagaimana sebuah [PaymentSettlement]
 * menggerakkan tagihan menjadi lunas dan membentuk catatan [Payment] yang konsisten.
 * Murni domain — tanpa Spring, repository, maupun gateway nyata.
 */
class PaymentSettlementFlowTest {

    private fun issuedInvoice(amount: BigDecimal = BigDecimal("200000")): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        number = "INV-202607-0007",
        periodStart = LocalDate.of(2026, 7, 1),
        periodEnd = LocalDate.of(2026, 7, 31),
        amount = amount,
        dueDate = LocalDate.of(2026, 7, 8),
    )

    @Test
    fun `settlement melunasi tagihan menunggak`() {
        val invoice = issuedInvoice()
        invoice.markOverdue()
        val settlement = PaymentSettlement(
            invoiceNumber = invoice.number,
            gatewayRef = "trx-99",
            amount = invoice.amount,
            paidAt = Instant.parse("2026-07-12T09:00:00Z"),
            provider = "MANUAL",
        )

        invoice.markPaid(settlement.paidAt)

        assertThat(invoice.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(invoice.paidAt).isEqualTo(settlement.paidAt)
    }

    @Test
    fun `payment dibentuk dari settlement dengan nilai konsisten`() {
        val invoice = issuedInvoice(BigDecimal("200000"))
        val settlement = PaymentSettlement(
            invoiceNumber = invoice.number,
            gatewayRef = "trx-99",
            amount = invoice.amount,
            paidAt = Instant.parse("2026-07-12T09:00:00Z"),
            provider = "MANUAL",
        )

        val payment = Payment.create(
            tenantId = invoice.tenantId,
            invoiceId = invoice.id,
            customerId = invoice.customerId,
            amount = settlement.amount,
            provider = settlement.provider,
            gatewayRef = settlement.gatewayRef,
            paidAt = settlement.paidAt,
            note = "transfer BCA",
        )

        assertThat(payment.invoiceId).isEqualTo(invoice.id)
        assertThat(payment.customerId).isEqualTo(invoice.customerId)
        assertThat(payment.amount).isEqualByComparingTo("200000")
        assertThat(payment.provider).isEqualTo("MANUAL")
        assertThat(payment.gatewayRef).isEqualTo("trx-99")
        assertThat(payment.paidAt).isEqualTo(settlement.paidAt)
        assertThat(payment.note).isEqualTo("transfer BCA")
    }

    @Test
    fun `settlement berulang idempoten pada tagihan yang sudah lunas`() {
        val invoice = issuedInvoice()
        val paidAt = Instant.parse("2026-07-12T09:00:00Z")
        invoice.markPaid(paidAt)

        // Callback kedua (retry gateway) tidak menggeser waktu lunas.
        invoice.markPaid(Instant.parse("2026-07-20T00:00:00Z"))

        assertThat(invoice.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(invoice.paidAt).isEqualTo(paidAt)
    }
}
