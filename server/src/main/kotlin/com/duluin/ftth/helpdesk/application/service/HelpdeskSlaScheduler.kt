package com.duluin.ftth.helpdesk.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.helpdesk.TicketSlaBreached
import com.duluin.ftth.helpdesk.application.port.outbound.TicketRepository
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Penjaga janji waktu helpdesk: menyapu tiket yang tenggatnya sudah lewat lalu meneriakkannya
 * satu kali sebagai [TicketSlaBreached].
 *
 * Kenapa perlu penyapu berkala, padahal "lewat SLA" bisa dihitung saat halaman dibuka? Karena
 * tiket yang paling mungkin terlewat justru yang tak pernah dibuka siapa pun. Antrean yang
 * hanya jujur ketika dilihat adalah antrean yang membiarkan keluhan Sabtu malam mengendap
 * sampai Senin.
 *
 * Berjalan di luar konteks request, jadi tenant dipasang satu per satu lewat
 * [TenantContext.runAs] — sama seperti scheduler penagihan, CPE, dan FUP. Kegagalan satu
 * tenant tak menghentikan tenant lain.
 */
@Component
class HelpdeskSlaScheduler(
    private val tenantApi: TenantApi,
    private val worker: HelpdeskSlaSweeper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.helpdesk.sla-scan-interval:PT5M}")
    fun scanSla() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { worker.run() } }
                .onFailure { log.warn("Sapuan SLA helpdesk tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Penyapu SLA satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [HelpdeskSlaScheduler], bukan method privat: `@Transactional` Spring
 * berlaku lewat proxy, jadi pemanggilan dari dalam kelas yang sama tak akan pernah dibungkus
 * transaksi. REQUIRES_NEW mengurung kegagalan ke satu tenant.
 */
@Component
class HelpdeskSlaSweeper(
    private val tickets: TicketRepository,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun run() = sweep(Instant.now())

    /**
     * Dipisah dari [run] agar bisa diuji dengan jam tetap. Hanya tiket yang BELUM pernah
     * diteriakkan yang diambil, lalu ditandai — jadi satu pelanggaran menghasilkan satu
     * teriakan, bukan satu tiap lima menit sampai seseorang menyerah dan mematikan
     * notifikasinya. Penanda itu dibersihkan lagi begitu tiketnya bergerak (dibalas,
     * dibuka ulang, prioritasnya diubah), sehingga ronde berikutnya bisa berteriak lagi.
     */
    fun sweep(now: Instant) {
        val overdue = tickets.findOverdue(now, onlyUnalerted = true)
        if (overdue.isEmpty()) return

        overdue.forEach { ticket ->
            ticket.markSlaAlerted(now)
            tickets.save(ticket)
            events.publishEvent(ticket.toBreachedEvent(now))
        }
        log.info("Helpdesk: {} tiket melewati SLA", overdue.size)
    }

    /**
     * Satu tiket menghasilkan SATU event walau kedua tenggatnya lewat sekaligus: yang menerima
     * adalah manusia dengan satu antrean perhatian, dan dua pemberitahuan untuk satu tiket
     * yang sama hanya menambah kebisingan tanpa menambah informasi.
     *
     * Tenggat balasan menang saat keduanya lewat — pelanggan yang belum dijawab sama sekali
     * lebih mendesak daripada yang sudah dijawab tapi belum tuntas.
     */
    private fun Ticket.toBreachedEvent(now: Instant) = TicketSlaBreached(
        tenantId = tenantId,
        ticketId = id,
        code = code,
        subject = subject,
        customerName = customerName,
        priority = priority.name,
        overdueKind = if (responseOverdue(now)) "RESPONSE" else "RESOLUTION",
        dueAt = if (responseOverdue(now)) requireNotNull(responseDueAt) else resolutionDueAt,
        assigneeId = assigneeId,
    )
}
