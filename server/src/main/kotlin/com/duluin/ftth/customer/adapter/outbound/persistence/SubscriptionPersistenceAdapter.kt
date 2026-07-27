package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SubscriptionPersistenceAdapter(
    private val jpa: SubscriptionJpaRepository,
) : SubscriptionRepository {

    override fun save(subscription: Subscription): Subscription {
        val entity = jpa.findById(subscription.id).orElse(null)?.apply {
            packageName = subscription.packageName
            bandwidthMbps = subscription.bandwidthMbps
            monthlyFee = subscription.monthlyFee
            status = subscription.status
            activatedAt = subscription.activatedAt
            terminatedAt = subscription.terminatedAt
        } ?: SubscriptionJpaEntity(
            id = subscription.id,
            customerId = subscription.customerId,
            packageName = subscription.packageName,
            bandwidthMbps = subscription.bandwidthMbps,
            monthlyFee = subscription.monthlyFee,
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

    override fun findBillableForCurrentTenant(): List<Subscription> =
        jpa.findByStatusIn(listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.ISOLATED)).map { it.toDomain() }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun SubscriptionJpaEntity.toDomain(): Subscription = Subscription.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    customerId = customerId,
    packageName = packageName,
    bandwidthMbps = bandwidthMbps,
    monthlyFee = monthlyFee,
    status = status,
    activatedAt = activatedAt,
    terminatedAt = terminatedAt,
)
