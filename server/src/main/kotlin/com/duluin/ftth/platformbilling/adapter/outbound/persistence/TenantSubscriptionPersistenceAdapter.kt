package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Adapter langganan tenant. Platform-level (tanpa RLS). */
@Component
class TenantSubscriptionPersistenceAdapter(
    private val jpa: TenantSubscriptionJpaRepository,
) : TenantSubscriptionRepository {

    override fun findByTenantId(tenantId: UUID): TenantSubscription? =
        jpa.findByTenantId(tenantId)?.toDomain()

    override fun findById(id: UUID): TenantSubscription? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findDueForInvoice(onOrBefore: LocalDate): List<TenantSubscription> =
        jpa.findDueForInvoice(
            onOrBefore,
            listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE),
        ).map { it.toDomain() }

    override fun save(subscription: TenantSubscription): TenantSubscription {
        val entity = jpa.findById(subscription.id).orElse(null)?.apply {
            monthlyFee = subscription.monthlyFee
            status = subscription.status
            billingDay = subscription.billingDay
            graceDays = subscription.graceDays
            currentPeriodStart = subscription.currentPeriodStart
            currentPeriodEnd = subscription.currentPeriodEnd
            nextInvoiceAt = subscription.nextInvoiceAt
            activatedAt = subscription.activatedAt
        } ?: TenantSubscriptionJpaEntity(
            id = subscription.id,
            tenantId = subscription.tenantId,
            monthlyFee = subscription.monthlyFee,
            status = subscription.status,
            billingDay = subscription.billingDay,
            graceDays = subscription.graceDays,
            currentPeriodStart = subscription.currentPeriodStart,
            currentPeriodEnd = subscription.currentPeriodEnd,
            nextInvoiceAt = subscription.nextInvoiceAt,
            activatedAt = subscription.activatedAt,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantSubscriptionJpaEntity.toDomain(): TenantSubscription = TenantSubscription.rehydrate(
        id = id,
        tenantId = tenantId,
        monthlyFee = monthlyFee,
        status = status,
        billingDay = billingDay,
        graceDays = graceDays,
        currentPeriodStart = currentPeriodStart,
        currentPeriodEnd = currentPeriodEnd,
        nextInvoiceAt = nextInvoiceAt,
        activatedAt = activatedAt,
    )
}
