package com.duluin.ftth.bng.application.service

import com.duluin.ftth.common.integration.BngActionsAcknowledged
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menuntaskan perintah BRAS saat collector meng-ACK-nya. Monitoring menerbitkan
 * [BngActionsAcknowledged] dari denyut yang membawa hasil eksekusi; bng menyerapnya di
 * sini tanpa monitoring pernah mengimpor bng (yang akan menjadi siklus module).
 *
 * AFTER_COMMIT: perintah hanya dituntaskan setelah monitoring benar-benar meng-commit
 * denyut pembawanya. Tenant context dipasang dari event (penerbitnya berjalan di konteks
 * collector, bukan pengguna). `fallbackExecution = true` agar event tanpa transaksi (mis.
 * pengujian) tetap diproses. Kegagalan di-log & tak menjatuhkan denyut penerbitnya. Sama
 * polanya dengan [BngSessionListener].
 */
@Component
class BngActionAckListener(
    private val bngActionService: BngActionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: BngActionsAcknowledged) {
        try {
            TenantContext.runAs(event.tenantId) { bngActionService.acknowledge(event.results) }
        } catch (ex: Exception) {
            log.warn("Penuntasan ACK perintah BRAS collector {} tenant {} gagal", event.collectorId, event.tenantId, ex)
        }
    }
}
