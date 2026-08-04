package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.iam.TenantOnboardedEvent
import com.duluin.ftth.platformbilling.application.port.inbound.ProvisionTenantSubscriptionUseCase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Membuat langganan SaaS untuk tenant yang baru di-onboard.
 *
 * Dipisah dari iam lewat event ([TenantOnboardedEvent]) untuk memutus siklus modul —
 * iam menerbitkan, platformbilling mendengar; tak ada ketergantungan statis iam →
 * platformbilling. [ProvisionTenantSubscriptionUseCase] idempotent, jadi aman bila
 * event terkirim ganda; bila listener sempat gagal, backfill saat start-up menambal.
 *
 * Berjalan AFTER_COMMIT (fallbackExecution=true agar tetap jalan bila onboarding tak
 * dibungkus transaksi): langganan baru dibuat setelah baris tenant benar-benar ter-commit.
 * Provisioning platform-level (tabel non-RLS), tak perlu tenant context.
 */
@Component
class TenantOnboardedListener(
    private val provisioner: ProvisionTenantSubscriptionUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: TenantOnboardedEvent) {
        try {
            provisioner.ensureForTenant(event.tenantId, event.monthlyFeeOverride)
        } catch (ex: Exception) {
            // Jangan gagalkan onboarding; backfill start-up akan menambal langganan yang belum dibuat.
            log.warn("Gagal membuat langganan SaaS untuk tenant {} saat onboarding", event.tenantId, ex)
        }
    }
}
