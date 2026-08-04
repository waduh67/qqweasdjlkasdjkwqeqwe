package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantSubscriptionPaymentJpaRepository : JpaRepository<TenantSubscriptionPaymentJpaEntity, UUID> {
    fun findByInvoiceIdOrderByPaidAtDesc(invoiceId: UUID): List<TenantSubscriptionPaymentJpaEntity>
}
