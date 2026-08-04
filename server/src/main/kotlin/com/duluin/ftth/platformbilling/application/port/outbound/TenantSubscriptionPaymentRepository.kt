package com.duluin.ftth.platformbilling.application.port.outbound

import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionPayment
import java.util.UUID

/** Akses pembayaran langganan tenant (append-only). Platform-level (tanpa RLS). */
interface TenantSubscriptionPaymentRepository {
    fun save(payment: TenantSubscriptionPayment): TenantSubscriptionPayment
    fun findByInvoiceId(invoiceId: UUID): List<TenantSubscriptionPayment>
}
