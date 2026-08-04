package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface TenantSubscriptionJpaRepository : JpaRepository<TenantSubscriptionJpaEntity, UUID> {
    fun findByTenantId(tenantId: UUID): TenantSubscriptionJpaEntity?

    @Query(
        """
        SELECT s FROM TenantSubscriptionJpaEntity s
        WHERE s.nextInvoiceAt IS NOT NULL
          AND s.nextInvoiceAt <= :onOrBefore
          AND s.status IN :statuses
        """,
    )
    fun findDueForInvoice(
        @Param("onOrBefore") onOrBefore: LocalDate,
        @Param("statuses") statuses: Collection<SubscriptionStatus>,
    ): List<TenantSubscriptionJpaEntity>
}
