package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.RefundStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Cacah tagihan per status — proyeksi beralias agar tak bergantung urutan kolom. */
interface InvoiceStatusCount {
    val status: InvoiceStatus
    val total: Long
}

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

    /** Lunas dalam rentang: `paidAt` non-null (hanya markPaid yang mengisinya) di [from]..[to). */
    fun findByPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
        from: Instant,
        toExclusive: Instant,
    ): List<InvoiceJpaEntity>

    /** Terbit dalam rentang [from]..[to). */
    fun findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(
        from: Instant,
        toExclusive: Instant,
    ): List<InvoiceJpaEntity>

    /** Tunggakan per [asOf]: OVERDUE, atau ISSUED yang jatuh temponya sebelum [asOf]. */
    @Query(
        """
        select i from InvoiceJpaEntity i
        where i.status = com.duluin.ftth.billing.domain.model.InvoiceStatus.OVERDUE
           or (i.status = com.duluin.ftth.billing.domain.model.InvoiceStatus.ISSUED and i.dueDate < :asOf)
        """,
    )
    fun findOutstanding(@Param("asOf") asOf: LocalDate): List<InvoiceJpaEntity>

    @Query("select i.status as status, count(i) as total from InvoiceJpaEntity i group by i.status")
    fun countGroupedByStatus(): List<InvoiceStatusCount>
}

interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, UUID> {
    fun findByInvoiceIdOrderByPaidAtDesc(invoiceId: UUID): List<PaymentJpaEntity>
    fun findByCustomerIdOrderByPaidAtDesc(customerId: UUID): List<PaymentJpaEntity>
}

interface RefundJpaRepository : JpaRepository<RefundJpaEntity, UUID> {
    fun findAllByOrderByRequestedAtDesc(): List<RefundJpaEntity>
    fun findByInvoiceIdOrderByRequestedAtDesc(invoiceId: UUID): List<RefundJpaEntity>
    fun findByGatewayRef(gatewayRef: String): RefundJpaEntity?

    /** Pengembalian BERHASIL yang tuntas di [from]..[to) — pengurang pendapatan periode itu. */
    fun findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
        status: RefundStatus,
        from: Instant,
        toExclusive: Instant,
    ): List<RefundJpaEntity>
}
