package com.duluin.ftth.inbox.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.helpdesk.TicketSlaBreached
import com.duluin.ftth.incident.IncidentOpened
import com.duluin.ftth.inbox.domain.model.NotificationKind
import com.duluin.ftth.inbox.domain.model.NotificationSeverity
import com.duluin.ftth.inbox.domain.model.OperatorNotification
import com.duluin.ftth.workorder.WorkOrderAssigned
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Menerjemahkan peristiwa module lain menjadi baris di lonceng operator.
 *
 * Kenapa kotak masuk yang MENDENGARKAN, bukan module penerbit yang menulis? Karena helpdesk,
 * incident, dan workorder tak perlu tahu bahwa lonceng itu ada — dan kalau nanti ada sumber
 * peristiwa baru, yang berubah cuma berkas ini. Arah ketergantungannya pun satu arah:
 * `inbox` mengenal permukaan publik mereka, mereka tak mengenal `inbox`.
 *
 * Semuanya pada fase AFTER_COMMIT: pemberitahuan tentang tiket yang ternyata gagal disimpan
 * adalah pemberitahuan yang menyuruh orang mengejar sesuatu yang tak pernah ada. Tenant
 * context dipasang dari event karena penjaga SLA & korelasi alarm berjalan di luar konteks
 * pengguna. Kegagalan cukup di-log — lonceng yang bermasalah tak boleh menggagalkan pekerjaan
 * yang menerbitkannya.
 */
@Component
class InboxEventListener(
    private val recorder: OperatorNotificationRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Tiket lewat janji waktu. Kalau sudah ada penanggung jawabnya, ini urusan PRIBADI dia;
     * kalau belum, seluruh pemegang `helpdesk.ticket.manage` yang perlu tahu — tiket tak
     * bertuan yang lewat SLA justru yang paling mungkin terlupakan.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: TicketSlaBreached) {
        record(event.tenantId, "SLA tiket ${event.ticketId}") {
            val tenggat = if (event.overdueKind == "RESPONSE") "balasan" else "penyelesaian"
            val title = "Tiket ${event.code} lewat tenggat $tenggat"
            val body = "${event.subject} — ${event.customerName}. " +
                "Tenggat $tenggat terlewat pada ${MOMENT_FORMAT.format(event.dueAt)}."
            // Kunci memuat tenggatnya: satu tiket yang bergerak lalu terlambat LAGI adalah
            // peristiwa baru yang pantas diteriakkan ulang, bukan gema yang sama.
            val dedupe = "helpdesk-sla:${event.ticketId}:${event.overdueKind}:${event.dueAt.epochSecond}"
            val severity =
                if (event.priority == "URGENT" || event.priority == "HIGH") {
                    NotificationSeverity.CRITICAL
                } else {
                    NotificationSeverity.WARNING
                }
            val assignee = event.assigneeId
            if (assignee != null) {
                listOf(
                    OperatorNotification.personal(
                        tenantId = event.tenantId,
                        kind = NotificationKind.HELPDESK_SLA,
                        severity = severity,
                        title = title,
                        body = body,
                        link = "/helpdesk?ticket=${event.ticketId}",
                        userId = assignee,
                        dedupeKey = dedupe,
                    ),
                )
            } else {
                listOf(
                    OperatorNotification.forHolders(
                        tenantId = event.tenantId,
                        kind = NotificationKind.HELPDESK_SLA,
                        severity = severity,
                        title = "$title (belum ada penanggung jawab)",
                        body = body,
                        link = "/helpdesk?ticket=${event.ticketId}",
                        permission = "helpdesk.ticket.manage",
                        dedupeKey = dedupe,
                    ),
                )
            }
        }
    }

    /** Gangguan baru: milik antrean bersama — siapa pun yang boleh melihat insiden. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentOpened) {
        record(event.tenantId, "insiden ${event.incidentId}") {
            listOf(
                OperatorNotification.forHolders(
                    tenantId = event.tenantId,
                    kind = NotificationKind.INCIDENT_OPENED,
                    severity = severityOf(event.severity),
                    title = "Gangguan baru: ${event.title}",
                    body = "${event.rootLabel} — ${event.affectedCustomerCount} pelanggan terdampak.",
                    link = "/incidents",
                    permission = "incident.ticket.view",
                    dedupeKey = "incident-opened:${event.incidentId}",
                ),
            )
        }
    }

    /**
     * Work order ditugaskan. Satu baris per teknisi (bukan satu baris untuk timnya): "sudah
     * saya baca" hanya bermakna kalau yang membaca adalah orang yang ditugaskan.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: WorkOrderAssigned) {
        record(event.tenantId, "WO ${event.workOrderId}") {
            val jadwal = event.scheduledAt?.let { " Dijadwalkan ${MOMENT_FORMAT.format(it)}." }.orEmpty()
            event.technicianIds.map { technicianId ->
                OperatorNotification.personal(
                    tenantId = event.tenantId,
                    kind = NotificationKind.WORK_ORDER_ASSIGNED,
                    severity = NotificationSeverity.INFO,
                    title = "Work order ${event.code} ditugaskan ke Anda",
                    body = "${event.title}.$jadwal",
                    // Menuju "Tugas Saya", bukan papan dispatch: teknisi lapangan kerap tak
                    // punya izin membuka papan itu sama sekali.
                    link = "/my-work-orders/${event.workOrderId}",
                    userId = technicianId,
                    dedupeKey = "wo-assigned:${event.workOrderId}:$technicianId",
                )
            }
        }
    }

    /** Nama [com.duluin.ftth.incident.domain.model.IncidentSeverity] → tingkat lonceng. */
    private fun severityOf(name: String): NotificationSeverity =
        runCatching { NotificationSeverity.valueOf(name) }.getOrDefault(NotificationSeverity.WARNING)

    private fun record(tenantId: UUID, what: String, build: () -> List<OperatorNotification>) {
        try {
            TenantContext.runAs(tenantId) { build().forEach(recorder::record) }
        } catch (ex: Exception) {
            log.warn("Kotak masuk operator gagal mencatat {} untuk tenant {}", what, tenantId, ex)
        }
    }

    private companion object {
        /** Zona server (VPS = Asia/Jakarta), selaras konvensi module lain yang pakai systemDefault. */
        val MOMENT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.forLanguageTag("id"))
                .withZone(ZoneId.systemDefault())
    }
}
