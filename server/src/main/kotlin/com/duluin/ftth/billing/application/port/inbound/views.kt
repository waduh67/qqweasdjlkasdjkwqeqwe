package com.duluin.ftth.billing.application.port.inbound

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Proyeksi satu tagihan untuk UI. [gatewayProvider]/[payUrl] disertakan agar UI bisa
 * menampilkan tautan bayar; referensi internal gateway ([gatewayRef]) sengaja tidak
 * dibocorkan lewat pandangan tagihan.
 */
data class InvoiceView(
    val id: UUID,
    val number: String,
    val customerId: UUID,
    val subscriptionId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    /** Total tagihan (sudah termasuk [taxAmount] PPN). */
    val amount: BigDecimal,
    /** Dasar sebelum PPN (DPP) = [amount] − [taxAmount]. */
    val baseAmount: BigDecimal,
    /** Komponen PPN dalam [amount]; nol bila tagihan tanpa PPN. */
    val taxAmount: BigDecimal,
    /** Tarif PPN yang diterapkan (mis. 0.1100); null bila tanpa PPN. */
    val taxRate: BigDecimal?,
    /** Tagihan diprorata (aktivasi tengah periode); [proratedDays] = hari yang ditagih. */
    val prorated: Boolean,
    val proratedDays: Int?,
    val status: String,
    val issuedAt: Instant,
    val dueDate: LocalDate,
    val paidAt: Instant?,
    val gatewayProvider: String?,
    val payUrl: String?,
)

/** Proyeksi satu pembayaran untuk UI. */
data class PaymentView(
    val id: UUID,
    val invoiceId: UUID,
    val customerId: UUID,
    val amount: BigDecimal,
    val provider: String,
    val gatewayRef: String?,
    val paidAt: Instant,
    val note: String?,
)
