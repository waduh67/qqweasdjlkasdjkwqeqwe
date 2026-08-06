package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.inbound.UpdateTaxSettingsCommand
import com.duluin.ftth.billing.application.port.outbound.BillingTaxSettingsRepository
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.service.TaxService
import com.duluin.ftth.billing.domain.model.BillingTaxSettings
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Menguji sisi operator pajak [TaxService] dengan fake port murni — tanpa Spring/DB:
 * penghitungan kewajiban (Σ PPN, peredaran bruto sebelum PPN, BHP/USO saat pelaporan
 * nyala vs mati), penjaga rentang tanggal, serta baca/ubah setelan + jejak audit.
 */
class TaxServiceTest {

    private val tenantId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    // --- Kewajiban ---

    @Test
    fun `obligation menjumlahkan PPN dan peredaran bruto lalu menghitung BHP USO`() {
        val paid = listOf(
            paidInvoice(base = "100000", rate = "0.11"), // PPN 11000
            paidInvoice(base = "200000", rate = "0.11"), // PPN 22000
        )
        val service = service(settings = enabled(regulatory = true), paid = paid)

        val view = service.obligation(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

        assertThat(view.ppnCollected).isEqualByComparingTo("33000")
        assertThat(view.regulatoryRevenueBase).isEqualByComparingTo("300000")
        assertThat(view.bhpAmount).isEqualByComparingTo("1500") // 300000 * 0.005
        assertThat(view.usoAmount).isEqualByComparingTo("3750") // 300000 * 0.0125
        assertThat(view.regulatoryObligation).isEqualByComparingTo("5250")
    }

    @Test
    fun `obligation dengan pelaporan mati menolkan BHP USO tapi tetap menjumlah PPN`() {
        val paid = listOf(paidInvoice(base = "100000", rate = "0.11"))
        val service = service(settings = enabled(regulatory = false), paid = paid)

        val view = service.obligation(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

        assertThat(view.ppnCollected).isEqualByComparingTo("11000")
        assertThat(view.regulatoryRevenueBase).isEqualByComparingTo("100000")
        assertThat(view.bhpAmount).isEqualByComparingTo("0")
        assertThat(view.usoAmount).isEqualByComparingTo("0")
        assertThat(view.regulatoryObligation).isEqualByComparingTo("0")
    }

    @Test
    fun `obligation tanpa tagihan lunas menghasilkan nol`() {
        val service = service(settings = enabled(regulatory = true), paid = emptyList())

        val view = service.obligation(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

        assertThat(view.ppnCollected).isEqualByComparingTo("0")
        assertThat(view.regulatoryObligation).isEqualByComparingTo("0")
    }

    @Test
    fun `obligation menolak rentang terbalik`() {
        val service = service(settings = enabled(regulatory = true), paid = emptyList())

        assertThatThrownBy {
            service.obligation(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1))
        }.isInstanceOf(ValidationException::class.java)
    }

    // --- Setelan ---

    @Test
    fun `get memetakan setelan tersimpan ke view`() {
        val service = service(settings = enabled(regulatory = true), paid = emptyList())

        val view = service.get()

        assertThat(view.ppnEnabled).isTrue()
        assertThat(view.regulatoryEnabled).isTrue()
        assertThat(view.ppnRate).isEqualByComparingTo("0.11")
    }

    @Test
    fun `update menyimpan setelan baru dan mencatat audit`() {
        val repo = FakeTaxSettingsRepository(BillingTaxSettings.defaultFor(tenantId))
        val service = service(settingsRepo = repo, paid = emptyList())

        val view = service.update(
            UpdateTaxSettingsCommand(
                ppnEnabled = true,
                ppnRate = BigDecimal("0.11"),
                regulatoryEnabled = true,
                bhpRate = BigDecimal("0.005"),
                usoRate = BigDecimal("0.0125"),
            ),
        )

        assertThat(view.ppnEnabled).isTrue()
        assertThat(view.regulatoryEnabled).isTrue()
        assertThat(repo.saved).isNotNull()
        assertThat(repo.saved!!.ppnEnabled).isTrue()
    }

    // --- Perkakas uji ---

    private fun enabled(regulatory: Boolean): BillingTaxSettings =
        BillingTaxSettings.defaultFor(tenantId).apply {
            update(
                ppnEnabled = true,
                ppnRate = BigDecimal("0.11"),
                regulatoryEnabled = regulatory,
                bhpRate = BigDecimal("0.005"),
                usoRate = BigDecimal("0.0125"),
            )
        }

    private fun paidInvoice(base: String, rate: String): Invoice = Invoice.create(
        tenantId = tenantId,
        customerId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        number = "INV-${UuidV7.generate()}",
        periodStart = LocalDate.of(2026, 7, 1),
        periodEnd = LocalDate.of(2026, 7, 31),
        baseAmount = BigDecimal(base),
        dueDate = LocalDate.of(2026, 7, 8),
        taxRate = BigDecimal(rate),
    ).apply { markPaid(Instant.parse("2026-07-05T00:00:00Z")) }

    private fun service(
        settings: BillingTaxSettings? = null,
        settingsRepo: FakeTaxSettingsRepository = FakeTaxSettingsRepository(settings),
        paid: List<Invoice>,
    ): TaxService = TaxService(
        settingsRepo,
        FakePaidInvoiceRepository(paid),
        AuditRecorder(ApplicationEventPublisher { }, NoUser),
    )

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull() = null
    }

    private class FakeTaxSettingsRepository(private val current: BillingTaxSettings?) : BillingTaxSettingsRepository {
        var saved: BillingTaxSettings? = null

        override fun find(): BillingTaxSettings? = current

        override fun save(settings: BillingTaxSettings): BillingTaxSettings {
            saved = settings
            return settings
        }
    }

    private class FakePaidInvoiceRepository(private val paid: List<Invoice>) : InvoiceRepository {
        override fun findPaidBetween(from: Instant, toExclusive: Instant): List<Invoice> = paid

        override fun save(invoice: Invoice) = throw UnsupportedOperationException()
        override fun findById(id: UUID) = throw UnsupportedOperationException()
        override fun findAll() = throw UnsupportedOperationException()
        override fun findByNumber(number: String) = throw UnsupportedOperationException()
        override fun findByCustomerId(customerId: UUID) = throw UnsupportedOperationException()
        override fun findByStatus(status: InvoiceStatus) = throw UnsupportedOperationException()
        override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun countForPeriod(periodStart: LocalDate) = throw UnsupportedOperationException()
        override fun findBillableOverdue(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun findRemindableDueSoon(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
        override fun hasOverdueForSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun findIssuedBetween(from: Instant, toExclusive: Instant) = throw UnsupportedOperationException()
        override fun findOutstanding(asOf: LocalDate) = throw UnsupportedOperationException()
        override fun countByStatus() = throw UnsupportedOperationException()
    }
}
