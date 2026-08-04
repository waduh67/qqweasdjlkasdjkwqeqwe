package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TenantSubscriptionInvoiceJpaRepository : JpaRepository<TenantSubscriptionInvoiceJpaEntity, UUID> {
    fun findByNumber(number: String): TenantSubscriptionInvoiceJpaEntity?

    fun findBySubscriptionIdOrderByIssuedAtDesc(subscriptionId: UUID): List<TenantSubscriptionInvoiceJpaEntity>

    @Query(
        """
        SELECT i FROM TenantSubscriptionInvoiceJpaEntity i
        WHERE i.subscriptionId = :subscriptionId
          AND i.status IN :statuses
        ORDER BY i.dueDate ASC
        """,
    )
    fun findOutstandingBySubscriptionId(
        @Param("subscriptionId") subscriptionId: UUID,
        @Param("statuses") statuses: Collection<SubscriptionInvoiceStatus>,
    ): List<TenantSubscriptionInvoiceJpaEntity>
}
