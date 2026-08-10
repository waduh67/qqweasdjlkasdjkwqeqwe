package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Component
class SubscriptionPersistenceAdapter(
    private val jpa: SubscriptionJpaRepository,
) : SubscriptionRepository {

    override fun save(subscription: Subscription): Subscription {
        val entity = jpa.findById(subscription.id).orElse(null)?.apply {
            planId = subscription.planId
            packageName = subscription.packageName
            bandwidthMbps = subscription.bandwidthMbps
            monthlyFee = subscription.monthlyFee
            prorateOnActivation = subscription.prorateOnActivation
            billingDayOfMonth = subscription.billingDayOfMonth
            graceDays = subscription.graceDays
            autoIsolir = subscription.autoIsolir
            status = subscription.status
            activatedAt = subscription.activatedAt
            terminatedAt = subscription.terminatedAt
        } ?: SubscriptionJpaEntity(
            id = subscription.id,
            customerId = subscription.customerId,
            planId = subscription.planId,
            packageName = subscription.packageName,
            bandwidthMbps = subscription.bandwidthMbps,
            monthlyFee = subscription.monthlyFee,
            prorateOnActivation = subscription.prorateOnActivation,
            billingDayOfMonth = subscription.billingDayOfMonth,
            graceDays = subscription.graceDays,
            autoIsolir = subscription.autoIsolir,
            status = subscription.status,
            activatedAt = subscription.activatedAt,
            terminatedAt = subscription.terminatedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Subscription? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByCustomerId(customerId: UUID): List<Subscription> =
        jpa.findByCustomerIdOrderByCreatedAtDesc(customerId).map { it.toDomain() }

    override fun findByCustomerIds(customerIds: Set<UUID>): List<Subscription> =
        if (customerIds.isEmpty()) emptyList() else jpa.findByCustomerIdIn(customerIds).map { it.toDomain() }

    override fun findByIds(ids: Set<UUID>): List<Subscription> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids).map { it.toDomain() }

    override fun findBillableForCurrentTenant(): List<Subscription> =
        jpa.findByStatusIn(BILLABLE_STATUSES).map { it.toDomain() }

    override fun countByStatus(): Map<SubscriptionStatus, Long> =
        jpa.countGroupedByStatus().associate { it.status to it.total }

    override fun sumMonthlyRecurringRevenue(): BigDecimal =
        jpa.sumMonthlyFeeByStatusIn(BILLABLE_STATUSES)

    override fun countActivatedBetween(from: Instant, toExclusive: Instant): Long =
        jpa.countByActivatedAtGreaterThanEqualAndActivatedAtLessThan(from, toExclusive)

    override fun countTerminatedBetween(from: Instant, toExclusive: Instant): Long =
        jpa.countByTerminatedAtGreaterThanEqualAndTerminatedAtLessThan(from, toExclusive)

    override fun countLiveAt(at: Instant): Long = jpa.countLiveAt(at)

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    private companion object {
        /** ACTIVE + ISOLATED = langganan yang masih ditagih (penghasil pendapatan berulang). */
        val BILLABLE_STATUSES = listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.ISOLATED)
    }
}

internal fun SubscriptionJpaEntity.toDomain(): Subscription = Subscription.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    customerId = customerId,
    planId = planId,
    packageName = packageName,
    bandwidthMbps = bandwidthMbps,
    monthlyFee = monthlyFee,
    prorateOnActivation = prorateOnActivation,
    billingDayOfMonth = billingDayOfMonth,
    graceDays = graceDays,
    autoIsolir = autoIsolir,
    status = status,
    activatedAt = activatedAt,
    terminatedAt = terminatedAt,
)
