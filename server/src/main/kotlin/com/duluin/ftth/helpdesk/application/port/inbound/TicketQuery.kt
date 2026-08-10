package com.duluin.ftth.helpdesk.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.helpdesk.domain.model.TicketAuthor
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketPriority
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import java.time.Instant
import java.util.UUID

/** Model-baca antrean helpdesk untuk operator (konsol). Pelanggan membaca lewat `HelpdeskApi`. */
interface TicketQuery {

    fun search(filter: TicketFilter, pageRequest: PageRequest): Page<TicketView>

    fun get(id: UUID): TicketDetail

    /** Cacah tiket per status untuk kartu antrean di kepala halaman. */
    fun summary(): TicketSummaryView
}

/**
 * Penyaring antrean; semua null = seluruh tiket tenant. [query] mencocokkan kode/judul/nama
 * pelanggan. [unassigned] menang atas [assigneeId] bila keduanya terisi. [overdue] hanya
 * menyaring saat `true` — "belum lewat SLA" tak pernah jadi tampilan tersendiri.
 */
data class TicketFilter(
    val query: String? = null,
    val status: TicketStatus? = null,
    val category: TicketCategory? = null,
    val customerId: UUID? = null,
    val assigneeId: UUID? = null,
    val unassigned: Boolean? = null,
    val overdue: Boolean? = null,
)

data class TicketView(
    val id: UUID,
    val code: String,
    val customerId: UUID,
    val customerName: String,
    val category: TicketCategory,
    val subject: String,
    val status: TicketStatus,
    val priority: TicketPriority,
    /** Operator pemegang tiket; null = masih di antrean bersama. */
    val assigneeId: UUID?,
    val assigneeName: String?,
    val workOrderId: UUID?,
    val workOrderCode: String?,
    val openedAt: Instant,
    val lastActivityAt: Instant,
    /** Balasan operator pertama sepanjang umur tiket — bahan laporan, bukan penanda SLA berjalan. */
    val firstResponseAt: Instant?,
    /** Tenggat balasan yang sedang ditunggu pelanggan; null = bola tak di tangan operator. */
    val responseDueAt: Instant?,
    val resolutionDueAt: Instant,
    /**
     * Sudah lewat tenggat pada saat dibaca. Dihitung di server, bukan diserahkan ke klien:
     * jam browser operator bisa meleset, dan "lewat SLA" adalah angka yang dilaporkan ke
     * manajemen — ia harus punya satu sumber kebenaran.
     */
    val responseOverdue: Boolean,
    val resolutionOverdue: Boolean,
    val resolvedAt: Instant?,
    val closedAt: Instant?,
)

/** Tiket + laporan awal + seluruh utas percakapannya. */
data class TicketDetail(
    val ticket: TicketView,
    val description: String,
    val messages: List<TicketMessageView>,
)

data class TicketMessageView(
    val author: TicketAuthor,
    val authorName: String,
    val body: String,
    val at: Instant,
)

/**
 * Isi antrean: yang belum disentuh, yang sedang ditangani, yang menunggu konfirmasi pelanggan,
 * plus dua angka yang menentukan ke mana penyelia harus menoleh duluan — yang belum dipegang
 * siapa pun dan yang sudah lewat janji waktu.
 */
data class TicketSummaryView(
    val open: Long,
    val inProgress: Long,
    val resolved: Long,
    val unassigned: Long,
    val overdue: Long,
)
