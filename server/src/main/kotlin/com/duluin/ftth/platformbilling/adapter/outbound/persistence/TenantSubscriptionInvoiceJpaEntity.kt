package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tagihan satu periode langganan tenant. Platform-level (tanpa RLS). */
@Entity
@Table(name = "tenant_subscription_invoice")
class TenantSubscriptionInvoiceJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "subscription_id", nullable = false)
    var subscriptionId: UUID,

    @Column(nullable = false, length = 40, unique = true)
    var number: String,

    @Column(name = "period_start", nullable = false)
    var periodStart: LocalDate,

    @Column(name = "period_end", nullable = false)
    var periodEnd: LocalDate,

    @Column(nullable = false, precision = 14, scale = 2)
    var amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SubscriptionInvoiceStatus,

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant,

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate,

    @Column(name = "paid_at")
    var paidAt: Instant?,

    @Column(name = "gateway_provider", length = 20)
    var gatewayProvider: String?,

    @Column(name = "gateway_ref", length = 255)
    var gatewayRef: String?,

    @Column(name = "pay_url", length = 1024)
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
) : BaseJpaEntity(id)
