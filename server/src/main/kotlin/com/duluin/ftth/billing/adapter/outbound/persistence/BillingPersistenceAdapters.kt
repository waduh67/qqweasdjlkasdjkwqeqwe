package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

@Component
class InvoicePersistenceAdapter(
    private val jpa: InvoiceJpaRepository,
) : InvoiceRepository {

    override fun save(invoice: Invoice): Invoice {
        val entity = jpa.findById(invoice.id).orElse(null)?.apply {
            // Identitas & nilai (customer/subscription/number/periode/amount/dueDate/issuedAt)
            // tak disentuh — hanya kolom daur-hidup yang berpindah.
            status = invoice.status
            paidAt = invoice.paidAt
            gatewayProvider = invoice.gatewayProvider
            gatewayRef = invoice.gatewayRef
            payUrl = invoice.payUrl
            dueSoonReminded = invoice.dueSoonReminded
        } ?: InvoiceJpaEntity(
            id = invoice.id,
            customerId = invoice.customerId,
            subscriptionId = invoice.subscriptionId,
            number = invoice.number,
            periodStart = invoice.periodStart,
            periodEnd = invoice.periodEnd,
            amount = invoice.amount,
            prorated = invoice.prorated,
            proratedDays = invoice.proratedDays,
            status = invoice.status,
            issuedAt = invoice.issuedAt,
            dueDate = invoice.dueDate,
            paidAt = invoice.paidAt,
            gatewayProvider = invoice.gatewayProvider,
            gatewayRef = invoice.gatewayRef,
            payUrl = invoice.payUrl,
            dueSoonReminded = invoice.dueSoonReminded,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Invoice? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<Invoice> = jpa.findAllByOrderByIssuedAtDesc().map { it.toDomain() }

    override fun findByNumber(number: String): Invoice? = jpa.findByNumber(number)?.toDomain()

    override fun findByCustomerId(customerId: UUID): List<Invoice> =
        jpa.findByCustomerIdOrderByIssuedAtDesc(customerId).map { it.toDomain() }

    override fun findByStatus(status: InvoiceStatus): List<Invoice> =
        jpa.findByStatusOrderByIssuedAtDesc(status).map { it.toDomain() }

    override fun existsForPeriod(subscriptionId: UUID, periodStart: LocalDate): Boolean =
        jpa.existsBySubscriptionIdAndPeriodStart(subscriptionId, periodStart)

    override fun countForPeriod(periodStart: LocalDate): Long = jpa.countByPeriodStart(periodStart)

    override fun findBillableOverdue(asOf: LocalDate): List<Invoice> =
        jpa.findByStatusAndDueDateBeforeOrderByIssuedAtAsc(InvoiceStatus.ISSUED, asOf).map { it.toDomain() }

    override fun findRemindableDueSoon(from: LocalDate, to: LocalDate): List<Invoice> =
        jpa.findByStatusAndDueSoonRemindedFalseAndDueDateBetweenOrderByDueDateAsc(InvoiceStatus.ISSUED, from, to)
            .map { it.toDomain() }

    override fun hasOverdueForSubscription(subscriptionId: UUID): Boolean =
        jpa.existsBySubscriptionIdAndStatus(subscriptionId, InvoiceStatus.OVERDUE)
}

@Component
class PaymentPersistenceAdapter(
    private val jpa: PaymentJpaRepository,
) : PaymentRepository {

    override fun save(payment: Payment): Payment {
        // Pembayaran append-only: selalu insert baru, tak pernah menimpa yang ada.
        val entity = PaymentJpaEntity(
            id = payment.id,
            invoiceId = payment.invoiceId,
            customerId = payment.customerId,
            amount = payment.amount,
            provider = payment.provider,
            gatewayRef = payment.gatewayRef,
            paidAt = payment.paidAt,
            note = payment.note,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByInvoiceId(invoiceId: UUID): List<Payment> =
        jpa.findByInvoiceIdOrderByPaidAtDesc(invoiceId).map { it.toDomain() }
}

internal fun InvoiceJpaEntity.toDomain(): Invoice = Invoice.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    customerId = customerId,
    subscriptionId = subscriptionId,
    number = number,
    periodStart = periodStart,
    periodEnd = periodEnd,
    amount = amount,
    prorated = prorated,
    proratedDays = proratedDays,
    status = status,
    issuedAt = issuedAt,
    dueDate = dueDate,
    paidAt = paidAt,
    gatewayProvider = gatewayProvider,
    gatewayRef = gatewayRef,
    payUrl = payUrl,
    dueSoonReminded = dueSoonReminded,
)

internal fun PaymentJpaEntity.toDomain(): Payment = Payment.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    invoiceId = invoiceId,
    customerId = customerId,
    amount = amount,
    provider = provider,
    gatewayRef = gatewayRef,
    paidAt = paidAt,
    note = note,
)
