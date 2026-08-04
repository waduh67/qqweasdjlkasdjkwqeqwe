package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Pembayaran atas tagihan langganan tenant (append-only). Platform-level (tanpa RLS). */
@Entity
@Table(name = "tenant_subscription_payment")
class TenantSubscriptionPaymentJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "invoice_id", nullable = false)
    var invoiceId: UUID,

    @Column(nullable = false, precision = 14, scale = 2)
    var amount: BigDecimal,

    @Column(nullable = false, length = 20)
    var provider: String,

    @Column(name = "gateway_ref", length = 255)
    var gatewayRef: String?,

    @Column(name = "paid_at", nullable = false)
    var paidAt: Instant,

    @Column(length = 500)
    var note: String?,
) : BaseJpaEntity(id)
