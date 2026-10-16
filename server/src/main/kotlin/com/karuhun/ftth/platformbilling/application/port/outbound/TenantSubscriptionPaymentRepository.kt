package com.karuhun.ftth.platformbilling.application.port.outbound

import com.karuhun.ftth.platformbilling.domain.model.TenantSubscriptionPayment
import java.util.UUID

/** Akses pembayaran langganan tenant (append-only). Platform-level (tanpa RLS). */
interface TenantSubscriptionPaymentRepository {
    fun save(payment: TenantSubscriptionPayment): TenantSubscriptionPayment
    fun findByInvoiceId(invoiceId: UUID): List<TenantSubscriptionPayment>
}
