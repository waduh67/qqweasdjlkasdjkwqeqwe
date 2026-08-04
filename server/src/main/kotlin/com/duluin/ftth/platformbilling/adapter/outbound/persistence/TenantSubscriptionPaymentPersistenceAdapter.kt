package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionPaymentRepository
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionPayment
import org.springframework.stereotype.Component
import java.util.UUID

/** Adapter pembayaran langganan tenant (append-only). Platform-level (tanpa RLS). */
@Component
class TenantSubscriptionPaymentPersistenceAdapter(
    private val jpa: TenantSubscriptionPaymentJpaRepository,
) : TenantSubscriptionPaymentRepository {

    override fun save(payment: TenantSubscriptionPayment): TenantSubscriptionPayment {
        val entity = TenantSubscriptionPaymentJpaEntity(
            id = payment.id,
            tenantId = payment.tenantId,
            invoiceId = payment.invoiceId,
            amount = payment.amount,
            provider = payment.provider,
            gatewayRef = payment.gatewayRef,
            paidAt = payment.paidAt,
            note = payment.note,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByInvoiceId(invoiceId: UUID): List<TenantSubscriptionPayment> =
        jpa.findByInvoiceIdOrderByPaidAtDesc(invoiceId).map { it.toDomain() }

    private fun TenantSubscriptionPaymentJpaEntity.toDomain(): TenantSubscriptionPayment =
        TenantSubscriptionPayment.rehydrate(
            id = id,
            tenantId = tenantId,
            invoiceId = invoiceId,
            amount = amount,
            provider = provider,
            gatewayRef = gatewayRef,
            paidAt = paidAt,
            note = note,
        )
}
