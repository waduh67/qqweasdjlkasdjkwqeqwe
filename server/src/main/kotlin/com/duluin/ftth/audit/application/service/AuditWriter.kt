package com.duluin.ftth.audit.application.service

import com.duluin.ftth.audit.application.port.outbound.AuditRepository
import com.duluin.ftth.audit.domain.model.AuditEntry
import com.duluin.ftth.common.audit.AuditTrailEvent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Menuliskan satu entri audit. Dipanggil oleh [AuditEventListener] SETELAH tenant
 * context di-set, sehingga batas `@Transactional` di sini membuka session dengan
 * tenant yang benar (audit_log ber-RLS).
 */
@Service
class AuditWriter(
    private val auditRepository: AuditRepository,
) {
    // REQUIRES_NEW: listener berjalan pada fase AFTER_COMMIT saat transaksi lama
    // sedang menyelesaikan; butuh transaksi baru sendiri agar insert benar-benar commit.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(event: AuditTrailEvent) {
        auditRepository.save(
            AuditEntry.record(
                tenantId = event.tenantId,
                actorId = event.actorId,
                actorEmail = event.actorEmail,
                action = event.action,
                entityType = event.entityType,
                entityId = event.entityId,
                detail = event.detail,
            ),
        )
    }
}
