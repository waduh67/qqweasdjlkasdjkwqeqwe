package com.duluin.ftth.bng.application.service

import com.duluin.ftth.common.integration.BngSessionsReported
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menautkan kanal collector (module monitoring) ke jalur baca bng lewat seam event
 * di shared kernel — monitoring menerbitkan [BngSessionsReported], bng menyerapnya di
 * sini, tanpa monitoring pernah mengimpor bng (yang akan menjadi siklus module).
 *
 * AFTER_COMMIT: sesi hanya diserap setelah monitoring benar-benar meng-commit denyut
 * yang membawanya. Tenant context dipasang dari event karena penerbitnya berjalan di
 * konteks collector, bukan pengguna. `fallbackExecution = true` agar event yang terbit
 * tanpa transaksi (mis. pada pengujian) tetap diproses. Kegagalan penyerapan di-log dan
 * tidak menjatuhkan operasi monitoring yang menerbitkannya. Sama polanya dengan
 * [SubscriptionLifecycleListener].
 */
@Component
class BngSessionListener(
    private val ingest: BngSessionIngestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: BngSessionsReported) {
        try {
            TenantContext.runAs(event.tenantId) { ingest.ingest(event) }
        } catch (ex: Exception) {
            log.warn("Penyerapan sesi BRAS {} tenant {} gagal", event.nasId, event.tenantId, ex)
        }
    }
}
