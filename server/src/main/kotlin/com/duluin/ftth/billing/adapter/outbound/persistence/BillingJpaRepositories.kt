package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.InvoiceStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface InvoiceJpaRepository : JpaRepository<InvoiceJpaEntity, UUID> {
    fun findAllByOrderByIssuedAtDesc(): List<InvoiceJpaEntity>
    fun findByNumber(number: String): InvoiceJpaEntity?
    fun findByCustomerIdOrderByIssuedAtDesc(customerId: UUID): List<InvoiceJpaEntity>
    fun findByStatusOrderByIssuedAtDesc(status: InvoiceStatus): List<InvoiceJpaEntity>
    fun existsBySubscriptionIdAndPeriodStart(subscriptionId: UUID, periodStart: LocalDate): Boolean
    fun countByPeriodStart(periodStart: LocalDate): Long
    fun findByStatusAndDueDateBeforeOrderByIssuedAtAsc(status: InvoiceStatus, dueDate: LocalDate): List<InvoiceJpaEntity>
    fun findByStatusAndDueSoonRemindedFalseAndDueDateBetweenOrderByDueDateAsc(
        status: InvoiceStatus,
        from: LocalDate,
        to: LocalDate,
    ): List<InvoiceJpaEntity>
    fun existsBySubscriptionIdAndStatus(subscriptionId: UUID, status: InvoiceStatus): Boolean
}

interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, UUID> {
    fun findByInvoiceIdOrderByPaidAtDesc(invoiceId: UUID): List<PaymentJpaEntity>
}
