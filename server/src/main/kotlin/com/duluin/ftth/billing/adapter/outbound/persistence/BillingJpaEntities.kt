package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.RefundReason
import com.duluin.ftth.billing.domain.model.RefundStatus
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Tagihan. Identitas & nilai ([customerId], [subscriptionId], [number], periode,
 * [amount], [issuedAt], [dueDate]) tak berubah setelah dibuat → `updatable = false`;
 * hanya status daur-hidup dan referensi gateway yang berpindah.
 */
@Entity
@Table(name = "invoice")
class InvoiceJpaEntity(
    id: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "subscription_id", nullable = false, updatable = false)
    var subscriptionId: UUID,

    @Column(nullable = false, length = 40, updatable = false)
    var number: String,

    @Column(name = "period_start", nullable = false, updatable = false)
    var periodStart: LocalDate,

    @Column(name = "period_end", nullable = false, updatable = false)
    var periodEnd: LocalDate,

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    var amount: BigDecimal,

    /** Komponen PPN termasuk dalam [amount]; 0 untuk tagihan tanpa PPN (termasuk baris lama). */
    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2, updatable = false)
    var taxAmount: BigDecimal,

    /** Tarif PPN yang diterapkan (mis. 0.1100); null bila tanpa PPN. */
    @Column(name = "tax_rate", precision = 6, scale = 4, updatable = false)
    var taxRate: BigDecimal?,

    @Column(nullable = false, updatable = false)
    var prorated: Boolean,

    @Column(name = "prorated_days", updatable = false)
    var proratedDays: Int?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InvoiceStatus,

    @Column(name = "issued_at", nullable = false, updatable = false)
    var issuedAt: Instant,

    @Column(name = "due_date", nullable = false, updatable = false)
    var dueDate: LocalDate,

    @Column(name = "paid_at")
    var paidAt: Instant?,

    @Column(name = "gateway_provider", length = 40)
    var gatewayProvider: String?,

    @Column(name = "gateway_ref", length = 200)
    var gatewayRef: String?,

    @Column(name = "pay_url", length = 1000)
    var payUrl: String?,

    /** Instrumen bayar in-app terpilih (VIRTUAL_ACCOUNT/QR); null bila belum dipilih/charge REDIRECT. */
    @Column(name = "pay_method", length = 20)
    var payMethod: String?,

    @Column(name = "va_channel", length = 20)
    var vaChannel: String?,

    @Column(name = "va_number", length = 64)
    var vaNumber: String?,

    @Column(name = "va_name", length = 100)
    var vaName: String?,

    @Column(name = "va_expires_at")
    var vaExpiresAt: Instant?,

    @Column(name = "qr_content", columnDefinition = "text")
    var qrContent: String?,

    @Column(name = "qr_url", length = 1024)
    var qrUrl: String?,

    @Column(name = "qr_expires_at")
    var qrExpiresAt: Instant?,

    @Column(name = "due_soon_reminded", nullable = false)
    var dueSoonReminded: Boolean,

    /** Uang yang sudah dikembalikan ke pelanggan atas tagihan ini; 0 untuk tagihan yang tak direfund. */
    @Column(name = "refunded_amount", nullable = false, precision = 14, scale = 2)
    var refundedAmount: BigDecimal,
) : TenantAwareJpaEntity(id)

/**
 * Pembayaran. Bersifat append-only: seluruh kolomnya `updatable = false` — sebuah
 * pembayaran yang tercatat tidak pernah diubah. FK intra-module ke [invoiceId].
 */
@Entity
@Table(name = "payment")
class PaymentJpaEntity(
    id: UUID,

    @Column(name = "invoice_id", nullable = false, updatable = false)
    var invoiceId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    var amount: BigDecimal,

    @Column(nullable = false, length = 40, updatable = false)
    var provider: String,

    @Column(name = "gateway_ref", length = 200, updatable = false)
    var gatewayRef: String?,

    @Column(name = "paid_at", nullable = false, updatable = false)
    var paidAt: Instant,

    @Column(length = 500, updatable = false)
    var note: String?,
) : TenantAwareJpaEntity(id)

/**
 * Pengembalian dana. Permintaan & nilainya tak berubah setelah diajukan (`updatable = false`);
 * yang berpindah hanya status, referensi penyedia, alasan gagal, dan waktu selesainya.
 */
@Entity
@Table(name = "refund")
class RefundJpaEntity(
    id: UUID,

    @Column(name = "invoice_id", nullable = false, updatable = false)
    var invoiceId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "payment_id", updatable = false)
    var paymentId: UUID?,

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    var amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    var reason: RefundReason,

    @Column(nullable = false, length = 40, updatable = false)
    var provider: String,

    @Column(length = 200, updatable = false)
    var note: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RefundStatus,

    @Column(name = "gateway_ref", length = 200)
    var gatewayRef: String?,

    @Column(name = "failure_reason", length = 200)
    var failureReason: String?,

    @Column(name = "requested_at", nullable = false, updatable = false)
    var requestedAt: Instant,

    @Column(name = "completed_at")
    var completedAt: Instant?,
) : TenantAwareJpaEntity(id)
