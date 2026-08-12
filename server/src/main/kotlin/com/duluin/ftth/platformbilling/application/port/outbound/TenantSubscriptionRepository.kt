package com.duluin.ftth.platformbilling.application.port.outbound

import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import java.time.LocalDate
import java.util.UUID

/** Akses langganan tenant (satu baris per tenant). Platform-level (tanpa RLS). */
interface TenantSubscriptionRepository {
    fun findByTenantId(tenantId: UUID): TenantSubscription?
    fun findById(id: UUID): TenantSubscription?
    fun save(subscription: TenantSubscription): TenantSubscription

    /**
     * Langganan yang jatuh tempo diterbitkan tagihan ([TenantSubscription.nextInvoiceAt] <=
     * [onOrBefore]) dan masih menagih (ACTIVE/PAST_DUE). Dipakai scheduler penerbitan.
     */
    fun findDueForInvoice(onOrBefore: LocalDate): List<TenantSubscription>

    /**
     * Semua langganan SUSPENDED — yakni tenant yang konsolnya baca-saja. Dipakai backfill
     * start-up untuk memulihkan tenant yang terlanjur di-suspend keras oleh aturan lama.
     */
    fun findSuspended(): List<TenantSubscription>
}
