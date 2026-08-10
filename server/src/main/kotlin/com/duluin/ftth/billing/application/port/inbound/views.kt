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
    /** Instrumen bayar in-app terpilih (VIRTUAL_ACCOUNT/QR) & instruksinya; null bila belum pilih. */
    val payMethod: String? = null,
    val vaChannel: String? = null,
    val vaNumber: String? = null,
    val vaName: String? = null,
    val vaExpiresAt: Instant? = null,
    /** String QRIS mentah (dirender jadi kode QR di klien). */
    val qrContent: String? = null,
    val qrUrl: String? = null,
    val qrExpiresAt: Instant? = null,
    /**
     * Tagihan ini bisa dipaksa lunas/kedaluwarsa lewat simulasi sandbox penyedia (alat uji): Pivot
     * dalam mode sandbox, sesi bayar sudah dibuat, dan tagihan masih tertunggak. UI memakainya
     * untuk memunculkan aksi "Simulasi bayar" — di produksi selalu `false`.
     */
    val simulatable: Boolean = false,
    /**
     * Id sesi bayar penyedia (`gateway_ref`, mis. `data.id` Pivot) — dibuka HANYA saat [simulatable]
     * agar penguji bisa menyalinnya ke panel simulasi platform. Di produksi selalu `null`.
     */
    val paymentSessionId: String? = null,
    /** Uang yang sudah dikembalikan ke pelanggan atas tagihan ini; nol untuk tagihan biasa. */
    val refundedAmount: BigDecimal? = null,
    /** Sisa yang masih boleh dikembalikan — dipakai UI membatasi isian nominal refund. */
    val refundableAmount: BigDecimal? = null,
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

/**
 * Proyeksi satu pengembalian dana untuk UI. [status] & [reason] dikirim sebagai nama enum
 * (label Indonesianya dirakit di klien, sama seperti status tagihan).
 */
data class RefundView(
    val id: UUID,
    val invoiceId: UUID,
    val invoiceNumber: String?,
    val customerId: UUID,
    val amount: BigDecimal,
    val reason: String,
    val status: String,
    val provider: String,
    val note: String?,
    val failureReason: String?,
    val requestedAt: Instant,
    val completedAt: Instant?,
)
