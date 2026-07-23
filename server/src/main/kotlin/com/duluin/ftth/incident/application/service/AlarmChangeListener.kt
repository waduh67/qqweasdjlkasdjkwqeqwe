package com.duluin.ftth.incident.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Mendengarkan [AlarmsChangedEvent] dari monitoring dan mendamaikan insiden untuk
 * tenant yang bersangkutan.
 *
 * Berjalan pada fase AFTER_COMMIT: korelasi hanya boleh melihat alarm yang
 * benar-benar ter-commit — kalau tidak, insiden bisa dibuka untuk alarm yang
 * transaksinya justru di-rollback. Tenant context dipasang dari event, bukan
 * thread saat ini, karena penerbitnya (mis. denyut collector) berjalan tanpa
 * pengguna. `fallbackExecution = true` agar event yang terbit di luar transaksi
 * tetap diproses. Kegagalan korelasi tidak boleh menggagalkan operasi penerbit.
 */
@Component
class AlarmChangeListener(
    private val reconciler: IncidentReconciler,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: AlarmsChangedEvent) {
        try {
            TenantContext.runAs(event.tenantId) {
                reconciler.reconcile(event.tenantId)
            }
        } catch (ex: Exception) {
            log.warn("Korelasi insiden gagal untuk tenant {}", event.tenantId, ex)
        }
    }
}
