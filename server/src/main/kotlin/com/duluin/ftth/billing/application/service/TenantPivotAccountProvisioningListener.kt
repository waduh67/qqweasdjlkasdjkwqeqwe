package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ProvisionTenantPivotAccountUseCase
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.TenantOnboardedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Membuatkan sub-account Pivot NON_KYC untuk tenant yang baru di-onboard (keputusan: auto NON_KYC
 * saat onboarding — transaksi atas nama platform, tenant bisa upgrade KYC sendiri).
 *
 * Dipisah dari iam lewat [TenantOnboardedEvent] untuk memutus siklus modul. Terpisah pula dari
 * `platformbilling.TenantOnboardedListener` (yang hanya mengurus langganan SaaS): satu event, dua
 * consumer independen. [ProvisionTenantPivotAccountUseCase] idempotent → aman bila event ganda.
 *
 * Berjalan AFTER_COMMIT (fallbackExecution=true) dalam `TenantContext.runAs(tenantId)` — sub-account
 * tenant-scoped (RLS + `@TenantId`), jadi konteks tenant harus dipasang sebelum transaksi service
 * dibuka. Kegagalan Pivot TIDAK menggagalkan onboarding — bisa diprovisi ulang manual di
 * `/payment-gateway`.
 */
@Component
class TenantPivotAccountProvisioningListener(
    private val provisioner: ProvisionTenantPivotAccountUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: TenantOnboardedEvent) {
        try {
            TenantContext.runAs(event.tenantId) {
                provisioner.ensureForTenant(event.tenantId)
            }
        } catch (ex: Exception) {
            log.warn("Gagal provisi sub-account Pivot untuk tenant {} saat onboarding", event.tenantId, ex)
        }
    }
}
