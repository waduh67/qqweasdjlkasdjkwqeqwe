package com.duluin.ftth.bng.application.service

import com.duluin.ftth.catalog.PlanUpdated
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menautkan perubahan paket (modul catalog) ke penegakan jaringan (modul bng): saat
 * atribut paket berubah, grup RADIUS-nya disinkronkan ulang & sesi hidup di-CoA.
 *
 * AFTER_COMMIT: hanya bereaksi pada perubahan paket yang benar-benar ter-commit. Tenant
 * context dipasang dari event (penerbitnya bisa di luar konteks pengguna).
 * `fallbackExecution = true` agar event tanpa transaksi (mis. pengujian) tetap diproses.
 * Kegagalan di-log & tak menggagalkan penyuntingan paket. Sama polanya dengan
 * [SubscriptionLifecycleListener] & [BngActionAckListener].
 */
@Component
class PlanSyncListener(
    private val planNetworkSync: PlanNetworkSync,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: PlanUpdated) {
        try {
            TenantContext.runAs(event.tenantId) { planNetworkSync.resync(event.planId) }
        } catch (ex: Exception) {
            log.warn("Sinkronisasi grup RADIUS paket {} tenant {} gagal", event.planId, event.tenantId, ex)
        }
    }
}
