package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.BillingTaxSettings
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import java.time.Instant
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

    /**
     * Tagihan terbit (ISSUED) yang JATUH TEMPO antara [from]..[to] (inklusif) dan belum
     * pernah dikirimi pengingat — kandidat pengingat "mendekati jatuh tempo".
     */
    fun findRemindableDueSoon(from: LocalDate, to: LocalDate): List<Invoice>

    /** Masih ada tagihan menunggak (OVERDUE) untuk langganan ini — penentu auto-pulih. */
    fun hasOverdueForSubscription(subscriptionId: UUID): Boolean

    /**
     * Tagihan LUNAS yang `paidAt` berada di [from]..[toExclusive) — dasar pendapatan
     * tertagih & tren bulanan. Setengah-terbuka di ujung kanan supaya batas bulan tak
     * dobel-hitung.
     */
    fun findPaidBetween(from: Instant, toExclusive: Instant): List<Invoice>

    /** Tagihan yang TERBIT (`issuedAt`) di [from]..[toExclusive) — dasar nilai yang ditagihkan. */
    fun findIssuedBetween(from: Instant, toExclusive: Instant): List<Invoice>

    /** Tunggakan per [asOf]: berstatus OVERDUE, atau ISSUED yang jatuh temponya sebelum [asOf]. */
    fun findOutstanding(asOf: LocalDate): List<Invoice>

    /** Cacah seluruh tagihan tenant per status — potret distribusi untuk laporan. */
    fun countByStatus(): Map<InvoiceStatus, Long>
}

interface PaymentRepository {

    fun save(payment: Payment): Payment

    fun findByInvoiceId(invoiceId: UUID): List<Payment>

    /** Riwayat pembayaran seorang pelanggan, terbaru dulu — untuk portal self-service. */
    fun findByCustomerId(customerId: UUID): List<Payment>
}

/**
 * Persistence setelan pajak tenant. Satu baris per tenant (RLS + @TenantId menyaring ke
 * tenant aktif), jadi [find] mengembalikan baris tunggal — null bila belum pernah disetel.
 */
interface BillingTaxSettingsRepository {
    fun find(): BillingTaxSettings?
    fun save(settings: BillingTaxSettings): BillingTaxSettings
}
