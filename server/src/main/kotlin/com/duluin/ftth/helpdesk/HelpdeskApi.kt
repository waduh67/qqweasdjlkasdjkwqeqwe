package com.duluin.ftth.helpdesk

import java.time.Instant
import java.util.UUID

/**
 * Kontrak publik module helpdesk untuk module lain — hari ini `portal`, yang menyajikannya
 * ke pelanggan sebagai menu "Bantuan".
 *
 * SEMUA metode ter-scope ke satu pelanggan ([SubmitTicketCommand.customerId] / parameter
 * `customerId`): pemanggil mengisinya dari principal portal yang login, tak pernah dari input
 * klien. Tiket milik pelanggan lain dijawab "tidak ditemukan" — bukan "tidak berhak" — supaya
 * keberadaan tiket orang lain pun tak bocor.
 *
 * Bentuk viewnya berbeda dari sisi operator: enum diratakan jadi String dan nama operator
 * disamarkan menjadi "Tim dukungan", karena pelanggan tak perlu (dan tak boleh) tahu siapa
 * staf yang menangani.
 */
interface HelpdeskApi {

    /** Pelanggan melaporkan keluhan baru. */
    fun submit(command: SubmitTicketCommand): CustomerTicketDetail

    /** Daftar laporan pelanggan, terbaru dulu. */
    fun findTicketsOf(customerId: UUID): List<CustomerTicketView>

    fun findTicketOf(customerId: UUID, ticketId: UUID): CustomerTicketDetail

    fun replyAsCustomer(customerId: UUID, ticketId: UUID, body: String): CustomerTicketDetail

    /** Pelanggan menutup sendiri laporannya ("sudah beres"). */
    fun closeAsCustomer(customerId: UUID, ticketId: UUID): CustomerTicketDetail
}

/** [category] = nama `TicketCategory`; nilai tak dikenal ditolak sebagai input tak sah. */
data class SubmitTicketCommand(
    val customerId: UUID,
    val category: String,
    val subject: String,
    val description: String,
)

/** Kepala laporan seperti dibaca pelanggan. [status]/[category] = nama enum helpdesk. */
data class CustomerTicketView(
    val id: UUID,
    val code: String,
    val category: String,
    val subject: String,
    val status: String,
    /** Kode work order bila keluhannya sudah dijadwalkan ke teknisi; null bila belum. */
    val workOrderCode: String?,
    val openedAt: Instant,
    val lastActivityAt: Instant,
)

data class CustomerTicketDetail(
    val ticket: CustomerTicketView,
    val description: String,
    val messages: List<CustomerTicketMessageView>,
)

/** [author] = `CUSTOMER` / `OPERATOR` / `SYSTEM`. */
data class CustomerTicketMessageView(
    val author: String,
    val authorName: String,
    val body: String,
    val at: Instant,
)
