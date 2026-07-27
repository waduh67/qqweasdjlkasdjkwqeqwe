package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import java.time.LocalDate
import java.util.UUID

/**
 * Port persistence module billing. Kedua tabel tenant-aware (@TenantId + RLS), jadi
 * semua pencarian ter-scope tenant aktif secara otomatis — tak ada parameter tenantId
 * yang dibawa-bawa.
 */
interface InvoiceRepository {

    fun save(invoice: Invoice): Invoice

    fun findById(id: UUID): Invoice?

    /** Semua tagihan tenant aktif, terurut terbit terbaru — dipakai daftar tanpa filter. */
    fun findAll(): List<Invoice>

    fun findByNumber(number: String): Invoice?

    fun findByCustomerId(customerId: UUID): List<Invoice>

    fun findByStatus(status: InvoiceStatus): List<Invoice>

    /** Sudah ada tagihan langganan ini untuk periode tersebut — jaga anti-duplikat penerbitan. */
    fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate): Boolean

    /** Berapa tagihan tenant ini pada satu periode — dasar nomor urut penerbitan. */
    fun countForPeriod(periodStart: LocalDate): Long

    /** Tagihan terbit (ISSUED) yang sudah lewat [asOf] — kandidat penegakan tunggakan. */
    fun findBillableOverdue(asOf: LocalDate): List<Invoice>

    /** Masih ada tagihan menunggak (OVERDUE) untuk langganan ini — penentu auto-pulih. */
    fun hasOverdueForSubscription(subscriptionId: UUID): Boolean
}

interface PaymentRepository {

    fun save(payment: Payment): Payment

    fun findByInvoiceId(invoiceId: UUID): List<Payment>
}
