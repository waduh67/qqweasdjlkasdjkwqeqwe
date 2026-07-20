package com.duluin.ftth.audit.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Mendengarkan [AuditTrailEvent] dari seluruh module dan menuliskannya di
 * transaksi terpisah ([AuditWriter], REQUIRES_NEW) dengan tenant context yang
 * diambil dari event.
 *
 * Ditulis pada fase AFTER_COMMIT: audit hanya mencatat operasi yang benar-benar
 * ter-commit, dan referensi (mis. tenant baru saat onboarding) sudah terlihat
 * oleh transaksi audit yang terpisah. Tenant context dipasang dari event (bukan
 * thread saat ini), karena penerbit bisa berjalan tanpa/di tenant berbeda.
 * `fallbackExecution = true` agar event tanpa transaksi tetap tercatat.
 */
@Component
class AuditEventListener(
    private val auditWriter: AuditWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Best-effort: audit ditulis di transaksi REQUIRES_NEW terpisah, dan
    // kegagalannya tidak boleh menggagalkan operasi bisnis penerbit event.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: AuditTrailEvent) {
        try {
            TenantContext.runAs(event.tenantId) {
                auditWriter.record(event)
            }
        } catch (ex: Exception) {
            log.warn("Gagal menulis audit '{}' untuk tenant {}", event.action, event.tenantId, ex)
        }
    }
}
