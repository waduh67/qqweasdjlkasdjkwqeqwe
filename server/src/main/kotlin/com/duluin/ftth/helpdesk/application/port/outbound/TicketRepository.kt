package com.duluin.ftth.helpdesk.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.helpdesk.application.port.inbound.TicketFilter
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketMessage
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import java.time.Instant
import java.util.UUID

/**
 * Penyimpanan tiket. Agregat sengaja TIDAK membawa utas pesannya saat dibaca — antrean
 * operator hanya butuh kepala tiketnya — jadi utas diambil terpisah lewat [messagesOf],
 * sama seperti timeline insiden/work order.
 */
interface TicketRepository {

    /** Menyimpan tiket beserta pesan yang tertunda di agregatnya. */
    fun save(ticket: Ticket): Ticket

    fun findById(id: UUID): Ticket?

    /** Tiket seorang pelanggan, terbaru dulu (daftar di portal). */
    fun findByCustomer(customerId: UUID): List<Ticket>

    fun search(filter: TicketFilter, pageRequest: PageRequest): Page<Ticket>

    fun messagesOf(ticketId: UUID): List<TicketMessage>

    fun countByStatus(): Map<TicketStatus, Long>

    /** Tiket pelanggan yang belum ditutup — rem sederhana agar portal tak jadi corong spam. */
    fun countOpenOf(customerId: UUID): Long

    /** Tiket hidup yang belum dipegang siapa pun — angka yang paling sering ditanya penyelia. */
    fun countUnassigned(): Long

    /**
     * Tiket hidup yang salah satu tenggatnya sudah lewat pada [now]. Dipakai kartu ringkasan
     * dan penjaga SLA; [onlyUnalerted] menyaring yang belum pernah diteriakkan agar penjaga
     * tak mengulang tiket yang sama tiap ronde.
     */
    fun findOverdue(now: Instant, onlyUnalerted: Boolean = false): List<Ticket>

    /** Cacah tiket lewat tenggat tanpa menarik barisnya — kartu ringkasan memanggilnya tiap muat. */
    fun countOverdue(now: Instant): Long
}
