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
        baseAmount = amount,
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
                baseAmount = BigDecimal("1000"),
                dueDate = LocalDate.of(2026, 7, 8),
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    // --- Prorata: hitung hari terpakai saat aktivasi tengah periode ---

    private val jul1 = LocalDate.of(2026, 7, 1)
    private val jul31 = LocalDate.of(2026, 7, 31)

    @Test
    fun `prorate aktivasi tengah bulan menagih hari terpakai`() {
        // Aktif 16 Juli (bulan 31 hari) → 16 hari terpakai (16..31 inklusif).
        val result = Invoice.prorate(BigDecimal("310000"), LocalDate.of(2026, 7, 16), jul1, jul31)

        assertThat(result).isNotNull
        assertThat(result!!.days).isEqualTo(16)
        assertThat(result.amount).isEqualByComparingTo("160000") // 310000 * 16 / 31
        assertThat(result.amount.scale()).isEqualTo(2)
    }

    @Test
    fun `prorate hari terakhir periode menagih satu hari`() {
        val result = Invoice.prorate(BigDecimal("300000"), jul31, jul1, jul31)

        assertThat(result!!.days).isEqualTo(1)
        assertThat(result.amount).isEqualByComparingTo("9677.42") // 300000 * 1 / 31, HALF_UP
    }

    @Test
    fun `prorate membulatkan setengah ke atas pada skala 2`() {
        // Juni 30 hari, aktif tgl 11 → 20 hari; 100000 * 20 / 30 = 66666.666… → 66666.67
        val result = Invoice.prorate(
            BigDecimal("100000"), LocalDate.of(2026, 6, 11),
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
        )

        assertThat(result!!.days).isEqualTo(20)
        assertThat(result.amount).isEqualByComparingTo("66666.67")
    }

    @Test
    fun `prorate aktivasi hari pertama tidak diprorata`() {
        assertThat(Invoice.prorate(BigDecimal("310000"), jul1, jul1, jul31)).isNull()
    }

    @Test
    fun `prorate aktivasi sebelum periode tidak diprorata`() {
        assertThat(Invoice.prorate(BigDecimal("310000"), LocalDate.of(2026, 6, 20), jul1, jul31)).isNull()
    }

    @Test
    fun `prorate aktivasi setelah periode tidak diprorata`() {
        assertThat(Invoice.prorate(BigDecimal("310000"), LocalDate.of(2026, 8, 2), jul1, jul31)).isNull()
    }

    @Test
    fun `create dengan prorata menyimpan penanda dan hari`() {
        val invoice = Invoice.create(
            tenantId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            number = "INV-202607-0001",
            periodStart = jul1,
            periodEnd = jul31,
            baseAmount = BigDecimal("160000"),
            dueDate = LocalDate.of(2026, 7, 8),
            prorated = true,
            proratedDays = 16,
        )

        assertThat(invoice.prorated).isTrue()
        assertThat(invoice.proratedDays).isEqualTo(16)
    }

    @Test
    fun `create penuh secara default tidak diprorata`() {
        val invoice = newInvoice()

        assertThat(invoice.prorated).isFalse()
        assertThat(invoice.proratedDays).isNull()
    }

    @Test
    fun `create prorata tanpa hari valid ditolak`() {
        assertThatThrownBy {
            Invoice.create(
                tenantId = UuidV7.generate(),
                customerId = UuidV7.generate(),
                subscriptionId = UuidV7.generate(),
                number = "INV-202607-0001",
                periodStart = jul1,
                periodEnd = jul31,
                baseAmount = BigDecimal("160000"),
                dueDate = LocalDate.of(2026, 7, 8),
                prorated = true,
                proratedDays = 0,
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    // --- PPN: komponen pajak yang ditambahkan ke total tagihan ---

    private fun withTax(base: BigDecimal, rate: BigDecimal?): Invoice = Invoice.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        number = "INV-202607-0001",
        periodStart = jul1,
        periodEnd = jul31,
        baseAmount = base,
        dueDate = LocalDate.of(2026, 7, 8),
        taxRate = rate,
    )

    @Test
    fun `create dengan taxRate menambahkan PPN ke atas dasar`() {
        val invoice = withTax(BigDecimal("150000"), BigDecimal("0.11"))

        assertThat(invoice.baseAmount).isEqualByComparingTo("150000")
        assertThat(invoice.taxAmount).isEqualByComparingTo("16500") // 150000 * 0.11
        assertThat(invoice.amount).isEqualByComparingTo("166500") // dasar + PPN
        assertThat(invoice.taxRate).isEqualByComparingTo("0.11")
        assertThat(invoice.taxAmount.scale()).isEqualTo(2)
    }

    @Test
    fun `create tanpa taxRate tak berpajak — total sama dengan dasar`() {
        val invoice = withTax(BigDecimal("150000"), null)

        assertThat(invoice.taxAmount).isEqualByComparingTo("0")
        assertThat(invoice.taxRate).isNull()
        assertThat(invoice.amount).isEqualByComparingTo(invoice.baseAmount)
    }

    @Test
    fun `taxRate nol dianggap tanpa PPN`() {
        val invoice = withTax(BigDecimal("150000"), BigDecimal.ZERO)

        assertThat(invoice.taxAmount).isEqualByComparingTo("0")
        assertThat(invoice.taxRate).isNull()
    }

    @Test
    fun `PPN membulatkan setengah ke atas pada skala 2`() {
        // 9677.42 * 0.11 = 1064.5162 → 1064.52
        val invoice = withTax(BigDecimal("9677.42"), BigDecimal("0.11"))

        assertThat(invoice.taxAmount).isEqualByComparingTo("1064.52")
        assertThat(invoice.amount).isEqualByComparingTo("10741.94") // 9677.42 + 1064.52
    }

    @Test
    fun `taxRate negatif ditolak`() {
        assertThatThrownBy { withTax(BigDecimal("150000"), BigDecimal("-0.1")) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `taxRate satu atau lebih ditolak`() {
        assertThatThrownBy { withTax(BigDecimal("150000"), BigDecimal.ONE) }
            .isInstanceOf(ValidationException::class.java)
    }
}
