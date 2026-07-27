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
    val amount: BigDecimal,
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
