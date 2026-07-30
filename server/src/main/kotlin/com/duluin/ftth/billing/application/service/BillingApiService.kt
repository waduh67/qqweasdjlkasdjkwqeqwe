package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.BillingAccountSummary
import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
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
) : BillingApi {

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

    /**
     * Menunggak = OVERDUE, atau ISSUED yang sudah lewat jatuh tempo. Cermin persis
     * `isOutstanding` di UI lama; PAID/VOID dikecualikan.
     */
    private fun Invoice.isOutstanding(today: LocalDate): Boolean =
        status == InvoiceStatus.OVERDUE || (status == InvoiceStatus.ISSUED && dueDate.isBefore(today))
}
