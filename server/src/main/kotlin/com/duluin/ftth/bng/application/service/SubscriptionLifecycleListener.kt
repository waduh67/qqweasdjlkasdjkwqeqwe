package com.duluin.ftth.bng.application.service

import com.duluin.ftth.customer.SubscriptionPlanChanged
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menautkan daur hidup langganan (module customer) ke identitas jaringan (module bng).
 *
 * Berjalan pada fase AFTER_COMMIT: akun hanya diselaraskan dengan perubahan langganan
 * yang benar-benar ter-commit. Tenant context dipasang dari event — penerbitnya bisa
 * berjalan di luar konteks pengguna. `fallbackExecution = true` agar event yang terbit
 * tanpa transaksi tetap diproses. Kegagalan sinkronisasi di-log dan tidak menggagalkan
 * operasi langganan yang menerbitkannya. Langganan tanpa akun jaringan otomatis no-op.
 */
@Component
class SubscriptionLifecycleListener(
    private val lifecycle: SubscriberAccessLifecycle,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    /**
     * Langganan tanpa paket katalog (paket ad-hoc) dilewati: tak ada nilai jaringan yang bisa
     * diturunkan ke RADIUS, dan menebak-nebak lebih buruk daripada membiarkan apa adanya.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionPlanChanged) {
        val planId = event.planId ?: return
        try {
            TenantContext.runAs(event.tenantId) { lifecycle.onPlanChanged(event.subscriptionId, planId) }
        } catch (exception: Exception) {
            log.warn("Sinkronisasi paket akun jaringan gagal untuk tenant {}", event.tenantId, exception)
        }
    }
}
