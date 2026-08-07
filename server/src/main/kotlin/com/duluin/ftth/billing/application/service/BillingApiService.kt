package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.BillingAccountSummary
import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.BillingFinancialReport
import com.duluin.ftth.billing.CustomerInvoiceRef
import com.duluin.ftth.billing.CustomerPaymentRef
import com.duluin.ftth.billing.MonthlyRevenuePoint
import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.PaymentMethodOption
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * Implementasi [BillingApi] untuk modul lain (mis. aggregator Subscriber-360).
 * Menghitung ringkasan rekening dari daftar tagihan pelanggan DI SERVER — memindahkan
 * logika tunggakan yang dulu di browser (`web/.../billing.ts` + TagihanTab) agar angka
 * uang punya satu sumber kebenaran. [InvoiceRepository] tenant-aware (RLS) → hasil
 * ter-scope tenant aktif; tak ada parameter tenantId yang dibawa-bawa.
 */
@Service
@Transactional(readOnly = true)
class BillingApiService(
    private val invoiceRepository: InvoiceRepository,
    private val paymentRepository: PaymentRepository,
    private val invoiceCharger: InvoiceChargePort,
) : BillingApi {

    override fun findCustomerInvoices(customerId: UUID): List<CustomerInvoiceRef> =
        invoiceRepository.findByCustomerId(customerId).map { it.toRef() }

    override fun paymentMethods(): List<PaymentMethodOption> = PaymentMethodCatalog.methods

    @Transactional
    override fun payCustomerInvoice(
        customerId: UUID,
        invoiceId: UUID,
        method: String,
        channel: String?,
    ): CustomerInvoiceRef {
        // Batasi ke tagihan milik pelanggan ini — pelanggan portal tak boleh membayar tagihan orang lain.
        val invoice = invoiceRepository.findById(invoiceId)
            ?.takeIf { it.customerId == customerId }
            ?: throw NotFoundException("Tagihan tidak ditemukan")
        if (invoice.status != InvoiceStatus.ISSUED && invoice.status != InvoiceStatus.OVERDUE) {
            throw ValidationException("Tagihan ini tidak dapat dibayar (status ${invoice.status}).")
        }
        invoiceCharger.chargeWithMethod(invoice, method, channel)
        return invoiceRepository.save(invoice).toRef()
    }

    private fun Invoice.toRef() = CustomerInvoiceRef(
        id = id,
        number = number,
        periodStart = periodStart,
        periodEnd = periodEnd,
        amount = amount,
        status = status.name,
        issuedAt = issuedAt,
        dueDate = dueDate,
        paidAt = paidAt,
        gatewayProvider = gatewayProvider,
        payUrl = payUrl,
        payMethod = payMethod,
        vaChannel = vaChannel,
        vaNumber = vaNumber,
        vaName = vaName,
        vaExpiresAt = vaExpiresAt,
        qrContent = qrContent,
        qrUrl = qrUrl,
        qrExpiresAt = qrExpiresAt,
    )

    override fun findCustomerPayments(customerId: UUID): List<CustomerPaymentRef> =
        paymentRepository.findByCustomerId(customerId).map { pay ->
            CustomerPaymentRef(
                id = pay.id,
                invoiceId = pay.invoiceId,
                amount = pay.amount,
                provider = pay.provider,
                paidAt = pay.paidAt,
                note = pay.note,
            )
        }

    override fun findAccountSummary(customerId: UUID): BillingAccountSummary {
        val invoices = invoiceRepository.findByCustomerId(customerId)
        val today = LocalDate.now()
        val outstanding = invoices.filter { it.isOutstanding(today) }
        return BillingAccountSummary(
            customerId = customerId,
            outstandingAmount = outstanding.fold(BigDecimal.ZERO) { acc, inv -> acc + inv.amount },
            outstandingCount = outstanding.size,
            unpaidCount = invoices.count { it.status == InvoiceStatus.ISSUED || it.status == InvoiceStatus.OVERDUE },
            oldestDueDate = outstanding.minOfOrNull { it.dueDate },
            lastPaidAt = invoices.mapNotNull { it.paidAt }.maxOrNull(),
        )
    }

    override fun financialReport(from: LocalDate, to: LocalDate): BillingFinancialReport {
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toExclusive = to.plusDays(1).atStartOfDay(zone).toInstant()

        val paid = invoiceRepository.findPaidBetween(fromInstant, toExclusive)
        val issued = invoiceRepository.findIssuedBetween(fromInstant, toExclusive)
        val outstanding = invoiceRepository.findOutstanding(LocalDate.now())

        return BillingFinancialReport(
            revenueCollected = paid.sumOfAmount(),
            paidInvoiceCount = paid.size,
            issuedAmount = issued.sumOfAmount(),
            issuedInvoiceCount = issued.size,
            outstandingAmount = outstanding.sumOfAmount(),
            outstandingInvoiceCount = outstanding.size,
            statusCounts = invoiceRepository.countByStatus().entries.associate { (s, n) -> s.name to n.toInt() },
        )
    }

    override fun monthlyRevenue(fromMonth: YearMonth, toMonth: YearMonth): List<MonthlyRevenuePoint> {
        val fromInstant = fromMonth.atDay(1).atStartOfDay(zone).toInstant()
        val toExclusive = toMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()

        // Kelompokkan pembayaran menurut bulan kalender zona server (sama seperti penjadwal
        // yang memakai LocalDate.now()), lalu tebar ke SELURUH bulan rentang agar bolong ikut nol.
        val byMonth = invoiceRepository.findPaidBetween(fromInstant, toExclusive)
            .groupBy { YearMonth.from(it.paidAt!!.atZone(zone)) }

        val points = mutableListOf<MonthlyRevenuePoint>()
        var m = fromMonth
        while (!m.isAfter(toMonth)) {
            val invoices = byMonth[m].orEmpty()
            points += MonthlyRevenuePoint(
                month = m.toString(),
                revenue = invoices.sumOfAmount(),
                paidInvoiceCount = invoices.size,
            )
            m = m.plusMonths(1)
        }
        return points
    }

    private fun List<Invoice>.sumOfAmount(): BigDecimal =
        fold(BigDecimal.ZERO) { acc, inv -> acc + inv.amount }

    /**
     * Menunggak = OVERDUE, atau ISSUED yang sudah lewat jatuh tempo. Cermin persis
     * `isOutstanding` di UI lama; PAID/VOID dikecualikan.
     */
    private fun Invoice.isOutstanding(today: LocalDate): Boolean =
        status == InvoiceStatus.OVERDUE || (status == InvoiceStatus.ISSUED && dueDate.isBefore(today))

    private companion object {
        /** Batas hari→instant memakai zona server, selaras dengan penjadwal billing (LocalDate.now()). */
        val zone: ZoneId = ZoneId.systemDefault()
    }
}
