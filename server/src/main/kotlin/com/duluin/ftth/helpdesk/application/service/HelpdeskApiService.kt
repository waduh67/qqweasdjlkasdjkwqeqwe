package com.duluin.ftth.helpdesk.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.helpdesk.CustomerTicketDetail
import com.duluin.ftth.helpdesk.CustomerTicketMessageView
import com.duluin.ftth.helpdesk.CustomerTicketView
import com.duluin.ftth.helpdesk.HelpdeskApi
import com.duluin.ftth.helpdesk.SubmitTicketCommand
import com.duluin.ftth.helpdesk.application.port.outbound.TicketRepository
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketAuthor
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketMessage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Implementasi kontrak publik helpdesk — pintu masuk PELANGGAN (dipakai portal).
 *
 * Tiga hal yang membedakannya dari [TicketService] sisi operator:
 * 1. **Kepemilikan.** Setiap pembacaan/penulisan memeriksa tiketnya benar milik `customerId`
 *    yang diberikan pemanggil (principal portal). Tiket orang lain dijawab "tidak ditemukan".
 * 2. **Rem.** Jumlah laporan terbuka per pelanggan dibatasi, supaya portal tak berubah jadi
 *    corong spam yang menenggelamkan antrean operator.
 * 3. **Penyamaran.** Nama staf tak pernah sampai ke pelanggan — semua balasan operator tampil
 *    sebagai "Tim dukungan".
 */
@Service
@Transactional(readOnly = true)
class HelpdeskApiService(
    private val tickets: TicketRepository,
    private val customerApi: CustomerApi,
) : HelpdeskApi {

    @Transactional
    override fun submit(command: SubmitTicketCommand): CustomerTicketDetail {
        val customer = customerApi.findCustomer(command.customerId)
            ?: throw NotFoundException("Pelanggan tidak ditemukan")
        val category = TicketCategory.entries.firstOrNull { it.name == command.category }
            ?: throw ValidationException("Kategori '${command.category}' tidak dikenal")
        val terbuka = tickets.countOpenOf(command.customerId)
        if (terbuka >= MAX_OPEN_PER_CUSTOMER) {
            throw ValidationException(
                "Masih ada $terbuka laporan yang belum ditutup. " +
                    "Balas laporan yang sudah ada saja supaya penanganannya tak terpecah.",
            )
        }
        val ticket = Ticket.open(
            tenantId = TenantContext.tenantId(),
            customerId = customer.id,
            customerName = customer.name,
            category = category,
            subject = command.subject,
            description = command.description,
            at = Instant.now(),
        )
        return tickets.save(ticket).toCustomerDetail()
    }

    override fun findTicketsOf(customerId: UUID): List<CustomerTicketView> =
        tickets.findByCustomer(customerId).map { it.toCustomerView() }

    override fun findTicketOf(customerId: UUID, ticketId: UUID): CustomerTicketDetail =
        loadOwned(customerId, ticketId).toCustomerDetail()

    @Transactional
    override fun replyAsCustomer(customerId: UUID, ticketId: UUID, body: String): CustomerTicketDetail {
        val ticket = loadOwned(customerId, ticketId)
        ticket.replyByCustomer(body, Instant.now())
        return tickets.save(ticket).toCustomerDetail()
    }

    @Transactional
    override fun closeAsCustomer(customerId: UUID, ticketId: UUID): CustomerTicketDetail {
        val ticket = loadOwned(customerId, ticketId)
        ticket.closeByCustomer(Instant.now())
        return tickets.save(ticket).toCustomerDetail()
    }

    /**
     * Memuat tiket HANYA bila pemiliknya [customerId]. Tiket milik pelanggan lain menghasilkan
     * pesan yang sama persis dengan tiket yang tak ada — menjawab "tidak berhak" sama saja
     * dengan membenarkan bahwa tiket itu ada.
     */
    private fun loadOwned(customerId: UUID, ticketId: UUID): Ticket =
        tickets.findById(ticketId)?.takeIf { it.customerId == customerId }
            ?: throw NotFoundException("Laporan tidak ditemukan")

    private fun Ticket.toCustomerDetail() = CustomerTicketDetail(
        ticket = toCustomerView(),
        description = description,
        messages = tickets.messagesOf(id).map { it.toCustomerView(customerId) },
    )

    private companion object {
        /** Cukup longgar untuk keluhan yang benar-benar berbeda, cukup ketat untuk menahan banjir. */
        const val MAX_OPEN_PER_CUSTOMER = 5L

        /** Nama tampil balasan operator di mata pelanggan — identitas staf tak pernah dibuka. */
        const val SUPPORT_NAME = "Tim dukungan"

        const val SYSTEM_NAME = "Sistem"
    }

    private fun Ticket.toCustomerView() = CustomerTicketView(
        id = id,
        code = code,
        category = category.name,
        subject = subject,
        status = status.name,
        workOrderCode = workOrderCode,
        openedAt = openedAt,
        lastActivityAt = lastActivityAt,
    )

    /**
     * Jejak sistem pun bisa membawa nama staf (mis. "Status diubah menjadi selesai." oleh
     * operator yang mengubahnya), jadi penyamarannya diputuskan dari penulisnya: hanya pesan
     * milik pelanggan sendiri yang tampil bernama.
     */
    private fun TicketMessage.toCustomerView(ownerId: UUID) = CustomerTicketMessageView(
        author = author.name,
        authorName = when {
            author == TicketAuthor.CUSTOMER || authorId == ownerId -> authorName
            author == TicketAuthor.SYSTEM -> SYSTEM_NAME
            else -> SUPPORT_NAME
        },
        body = body,
        at = at,
    )
}
