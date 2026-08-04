package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Langganan tenant ke aplikasi (satu baris per tenant). Platform-level (tanpa RLS). */
@Entity
@Table(name = "tenant_subscription")
class TenantSubscriptionJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, unique = true)
    var tenantId: UUID,

    @Column(name = "monthly_fee", nullable = false, precision = 14, scale = 2)
    var monthlyFee: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SubscriptionStatus,

    @Column(name = "billing_day")
    var billingDay: Int?,

    @Column(name = "grace_days")
    var graceDays: Int?,

    @Column(name = "current_period_start")
    var currentPeriodStart: LocalDate?,

    @Column(name = "current_period_end")
    var currentPeriodEnd: LocalDate?,

    @Column(name = "next_invoice_at")
    var nextInvoiceAt: LocalDate?,

    @Column(name = "activated_at")
    var activatedAt: Instant?,
) : BaseJpaEntity(id)
