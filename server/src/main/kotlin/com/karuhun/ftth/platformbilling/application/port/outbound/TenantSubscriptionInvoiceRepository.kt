package com.karuhun.ftth.platformbilling.application.port.outbound

import com.karuhun.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import java.util.UUID

/** Akses tagihan langganan tenant. Platform-level (tanpa RLS). */
interface TenantSubscriptionInvoiceRepository {
    fun findById(id: UUID): TenantSubscriptionInvoice?
    fun findByNumber(number: String): TenantSubscriptionInvoice?
    fun findBySubscriptionId(subscriptionId: UUID): List<TenantSubscriptionInvoice>
    /** Tagihan belum lunas (ISSUED/OVERDUE) sebuah langganan — untuk penegakan & pemulihan. */
    fun findOutstandingBySubscriptionId(subscriptionId: UUID): List<TenantSubscriptionInvoice>
    fun save(invoice: TenantSubscriptionInvoice): TenantSubscriptionInvoice
}
