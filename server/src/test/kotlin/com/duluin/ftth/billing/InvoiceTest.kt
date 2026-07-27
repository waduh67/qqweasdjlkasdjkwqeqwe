package com.duluin.ftth.billing

import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Menguji mesin keadaan tagihan (markPaid/markOverdue/void beserta penjaganya) dan
 * validasi nilai/nomor — murni domain, tanpa Spring maupun database.
 */
class InvoiceTest {

    private fun newInvoice(amount: BigDecimal = BigDecimal("150000")): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        number = "INV-202607-0001",
        periodStart = LocalDate.of(2026, 7, 1),
        periodEnd = LocalDate.of(2026, 7, 31),
        amount = amount,
        dueDate = LocalDate.of(2026, 7, 8),
    )

    @Test
    fun `create menghasilkan tagihan ISSUED dengan skala amount 2`() {
        val invoice = newInvoice(BigDecimal("150000"))

        assertThat(invoice.status).isEqualTo(InvoiceStatus.ISSUED)
        assertThat(invoice.paidAt).isNull()
        assertThat(invoice.amount).isEqualByComparingTo("150000")
        assertThat(invoice.amount.scale()).isEqualTo(2)
        assertThat(invoice.gatewayProvider).isNull()
    }

    @Test
    fun `markPaid memindahkan ISSUED ke PAID dan mengisi paidAt`() {
        val invoice = newInvoice()
        val at = Instant.parse("2026-07-05T10:15:30Z")

        invoice.markPaid(at)

        assertThat(invoice.status).isEqualTo(InvoiceStatus.PAID)
        assertThat(invoice.paidAt).isEqualTo(at)
    }

    @Test
    fun `markPaid dari OVERDUE tetap PAID`() {
        val invoice = newInvoice()
        invoice.markOverdue()

        invoice.markPaid(Instant.now())

        assertThat(invoice.status).isEqualTo(InvoiceStatus.PAID)
    }

    @Test
    fun `markPaid idempoten tak mengubah paidAt saat sudah PAID`() {
        val invoice = newInvoice()
        val first = Instant.parse("2026-07-05T10:15:30Z")
        invoice.markPaid(first)

        invoice.markPaid(Instant.parse("2026-07-09T00:00:00Z"))

        assertThat(invoice.paidAt).isEqualTo(first)
    }

    @Test
    fun `markPaid atas tagihan VOID ditolak`() {
        val invoice = newInvoice()
        invoice.void()

        assertThatThrownBy { invoice.markPaid(Instant.now()) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `markOverdue hanya dari ISSUED`() {
        val invoice = newInvoice()

        invoice.markOverdue()

        assertThat(invoice.status).isEqualTo(InvoiceStatus.OVERDUE)
    }

    @Test
    fun `markOverdue atas tagihan PAID ditolak`() {
        val invoice = newInvoice()
        invoice.markPaid(Instant.now())

        assertThatThrownBy { invoice.markOverdue() }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `void dari ISSUED berhasil`() {
        val invoice = newInvoice()

        invoice.void()

        assertThat(invoice.status).isEqualTo(InvoiceStatus.VOID)
    }

    @Test
    fun `void atas tagihan PAID ditolak`() {
        val invoice = newInvoice()
        invoice.markPaid(Instant.now())

        assertThatThrownBy { invoice.void() }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `attachCharge melekatkan referensi gateway tanpa mengubah nilai`() {
        val invoice = newInvoice()

        invoice.attachCharge("MANUAL", "ref-123", "https://pay.example/abc")

        assertThat(invoice.gatewayProvider).isEqualTo("MANUAL")
        assertThat(invoice.gatewayRef).isEqualTo("ref-123")
        assertThat(invoice.payUrl).isEqualTo("https://pay.example/abc")
    }

    @Test
    fun `amount negatif ditolak`() {
        assertThatThrownBy { newInvoice(BigDecimal("-1")) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `nomor kosong ditolak`() {
        assertThatThrownBy {
            Invoice.create(
                tenantId = UuidV7.generate(),
                customerId = UuidV7.generate(),
                subscriptionId = UuidV7.generate(),
                number = "   ",
                periodStart = LocalDate.of(2026, 7, 1),
                periodEnd = LocalDate.of(2026, 7, 31),
                amount = BigDecimal("1000"),
                dueDate = LocalDate.of(2026, 7, 8),
            )
        }.isInstanceOf(ValidationException::class.java)
    }
}
