package com.duluin.ftth.bng.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.SubscriptionActivated
import com.duluin.ftth.customer.SubscriptionIsolated
import com.duluin.ftth.customer.SubscriptionTerminated
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionActivated) =
        sync(event.tenantId, "aktivasi") { lifecycle.onActivated(event.subscriptionId) }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionIsolated) =
        sync(event.tenantId, "isolir") { lifecycle.onIsolated(event.subscriptionId) }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionTerminated) =
        sync(event.tenantId, "terminasi") { lifecycle.onTerminated(event.subscriptionId) }

    private fun sync(tenantId: UUID, what: String, block: () -> Unit) {
        try {
            TenantContext.runAs(tenantId) { block() }
        } catch (ex: Exception) {
            log.warn("Sinkronisasi akun jaringan gagal saat {} untuk tenant {}", what, tenantId, ex)
        }
    }
}
