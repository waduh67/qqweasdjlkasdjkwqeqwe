package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.incident.IncidentApi
import com.duluin.ftth.incident.IncidentOpened
import com.duluin.ftth.notification.application.service.NotificationSender.Recipient
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menyiarkan pemberitahuan gangguan ke seluruh pelanggan terdampak begitu sebuah insiden
 * baru terbuka — pemicu `INCIDENT_OPENED`.
 *
 * Berjalan pada fase AFTER_COMMIT: daftar pelanggan terdampak baru bisa dihitung setelah
 * insiden benar-benar ter-commit ([IncidentApi.affectedContacts] membaca insiden itu). Tenant
 * context dipasang dari event karena korelasi insiden berjalan di luar konteks pengguna.
 * [NotificationSender.dispatchAuto] memutuskan kirim/tidak lewat saklar pemicu tenant; siaran
 * dicatat menaut ke [IncidentOpened.incidentId] persis seperti broadcast insiden manual.
 * Kegagalan cukup di-log agar tak menggagalkan korelasi insiden yang menerbitkannya.
 */
@Component
class IncidentNotificationListener(
    private val sender: NotificationSender,
    private val incidents: IncidentApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentOpened) {
        try {
            TenantContext.runAs(event.tenantId) {
                val recipients = incidents.affectedContacts(event.incidentId)
                    .map { Recipient(it.customerId, it.name, it.phone, it.email) }
                if (recipients.isEmpty()) return@runAs
                sender.dispatchAuto(
                    trigger = NotificationTrigger.INCIDENT_OPENED,
                    message = composeMessage(event),
                    recipients = recipients,
                    incidentId = event.incidentId,
                )
            }
        } catch (ex: Exception) {
            log.warn("Broadcast gangguan gagal untuk tenant {} insiden {}", event.tenantId, event.incidentId, ex)
        }
    }

    private fun composeMessage(event: IncidentOpened): String =
        "⚠️ Pemberitahuan Gangguan: ${event.title}. " +
            "Tim teknis kami sedang menangani gangguan pada ${event.rootLabel}. " +
            "Mohon maaf atas ketidaknyamanannya, layanan akan segera kami pulihkan."
}
