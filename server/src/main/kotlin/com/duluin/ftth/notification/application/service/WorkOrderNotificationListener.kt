package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.notification.application.service.NotificationSender.Recipient
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.workorder.WorkOrderAssigned
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Memberi tahu pelanggan jadwal kunjungan teknisi begitu work order ditugaskan — pemicu
 * `WORK_ORDER_SCHEDULED`.
 *
 * Hanya WO yang punya PELANGGAN dan sudah PUNYA jadwal yang diberitahukan: kerja
 * infrastruktur murni ([WorkOrderAssigned.customerId] null) atau WO tanpa jadwal
 * ([WorkOrderAssigned.scheduledAt] null) tak menyentuh pelanggan mana pun. Notifikasi ke
 * teknisi (push aplikasi lapangan) sengaja belum ditangani di sini.
 *
 * Berjalan pada fase AFTER_COMMIT; tenant context dipasang dari event karena penugasan bisa
 * berjalan di luar konteks pengguna. [NotificationSender.dispatchAuto] yang memutuskan
 * kirim/tidak lewat saklar pemicu tenant sekaligus memilih kanalnya. Kegagalan cukup di-log
 * agar tak menggagalkan penugasan WO.
 */
@Component
class WorkOrderNotificationListener(
    private val sender: NotificationSender,
    private val customers: CustomerApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: WorkOrderAssigned) {
        val customerId = event.customerId ?: return
        val scheduledAt = event.scheduledAt ?: return
        try {
            TenantContext.runAs(event.tenantId) {
                val customer = customers.findCustomer(customerId) ?: return@runAs
                sender.dispatchAuto(
                    trigger = NotificationTrigger.WORK_ORDER_SCHEDULED,
                    message = composeMessage(customer.name, event, scheduledAt),
                    recipients = listOf(Recipient(customer.id, customer.name, customer.phone, customer.email)),
                )
            }
        } catch (ex: Exception) {
            log.warn("Notifikasi jadwal WO gagal untuk tenant {} WO {}", event.tenantId, event.workOrderId, ex)
        }
    }

    private fun composeMessage(name: String, event: WorkOrderAssigned, scheduledAt: Instant): String =
        "Halo $name, kunjungan teknisi untuk \"${event.title}\" (WO ${event.code}) dijadwalkan pada " +
            "${SCHEDULE_FORMAT.format(scheduledAt)}. Mohon pastikan ada yang berada di lokasi. Terima kasih."

    private companion object {
        /** Zona server (VPS = Asia/Jakarta), selaras konvensi billing/bng yang pakai systemDefault. */
        val SCHEDULE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'pukul' HH:mm", Locale.forLanguageTag("id"))
                .withZone(ZoneId.systemDefault())
    }
}
