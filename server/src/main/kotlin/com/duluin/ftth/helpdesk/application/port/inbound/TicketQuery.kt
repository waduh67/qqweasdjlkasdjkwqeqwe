package com.duluin.ftth.helpdesk.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.helpdesk.domain.model.TicketAuthor
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
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

/** Penyaring antrean; semua null = seluruh tiket tenant. [query] mencocokkan kode/judul/nama pelanggan. */
data class TicketFilter(
    val query: String? = null,
    val status: TicketStatus? = null,
    val category: TicketCategory? = null,
    val customerId: UUID? = null,
)

data class TicketView(
    val id: UUID,
    val code: String,
    val customerId: UUID,
    val customerName: String,
    val category: TicketCategory,
    val subject: String,
    val status: TicketStatus,
    val workOrderId: UUID?,
    val workOrderCode: String?,
    val openedAt: Instant,
    val lastActivityAt: Instant,
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

/** Isi antrean: yang belum disentuh, yang sedang ditangani, dan yang menunggu konfirmasi pelanggan. */
data class TicketSummaryView(
    val open: Long,
    val inProgress: Long,
    val resolved: Long,
)
