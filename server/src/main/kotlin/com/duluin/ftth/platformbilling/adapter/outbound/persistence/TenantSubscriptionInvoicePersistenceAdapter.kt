package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import org.springframework.stereotype.Component
import java.util.UUID

/** Adapter tagihan langganan tenant. Platform-level (tanpa RLS). */
@Component
class TenantSubscriptionInvoicePersistenceAdapter(
    private val jpa: TenantSubscriptionInvoiceJpaRepository,
) : TenantSubscriptionInvoiceRepository {

    override fun findById(id: UUID): TenantSubscriptionInvoice? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findByNumber(number: String): TenantSubscriptionInvoice? =
        jpa.findByNumber(number)?.toDomain()

    override fun findBySubscriptionId(subscriptionId: UUID): List<TenantSubscriptionInvoice> =
        jpa.findBySubscriptionIdOrderByIssuedAtDesc(subscriptionId).map { it.toDomain() }

    override fun findOutstandingBySubscriptionId(subscriptionId: UUID): List<TenantSubscriptionInvoice> =
        jpa.findOutstandingBySubscriptionId(
            subscriptionId,
            listOf(SubscriptionInvoiceStatus.ISSUED, SubscriptionInvoiceStatus.OVERDUE),
        ).map { it.toDomain() }

    override fun save(invoice: TenantSubscriptionInvoice): TenantSubscriptionInvoice {
        val entity = jpa.findById(invoice.id).orElse(null)?.apply {
            status = invoice.status
            paidAt = invoice.paidAt
            gatewayProvider = invoice.gatewayProvider
            gatewayRef = invoice.gatewayRef
            payUrl = invoice.payUrl
        } ?: TenantSubscriptionInvoiceJpaEntity(
            id = invoice.id,
            tenantId = invoice.tenantId,
            subscriptionId = invoice.subscriptionId,
            number = invoice.number,
            periodStart = invoice.periodStart,
            periodEnd = invoice.periodEnd,
            amount = invoice.amount,
            status = invoice.status,
            issuedAt = invoice.issuedAt,
            dueDate = invoice.dueDate,
            paidAt = invoice.paidAt,
            gatewayProvider = invoice.gatewayProvider,
            gatewayRef = invoice.gatewayRef,
            payUrl = invoice.payUrl,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantSubscriptionInvoiceJpaEntity.toDomain(): TenantSubscriptionInvoice =
        TenantSubscriptionInvoice.rehydrate(
            id = id,
            tenantId = tenantId,
            subscriptionId = subscriptionId,
            number = number,
            periodStart = periodStart,
            periodEnd = periodEnd,
            amount = amount,
            status = status,
            issuedAt = issuedAt,
            dueDate = dueDate,
            paidAt = paidAt,
            gatewayProvider = gatewayProvider,
            gatewayRef = gatewayRef,
            payUrl = payUrl,
        )
}
