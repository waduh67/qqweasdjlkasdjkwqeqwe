package com.duluin.ftth.helpdesk.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.helpdesk.application.port.inbound.EscalateTicketCommand
import com.duluin.ftth.helpdesk.application.port.inbound.ManageTicketUseCase
import com.duluin.ftth.helpdesk.application.port.inbound.TicketDetail
import com.duluin.ftth.helpdesk.application.port.inbound.TicketFilter
import com.duluin.ftth.helpdesk.application.port.inbound.TicketMessageView
import com.duluin.ftth.helpdesk.application.port.inbound.TicketQuery
import com.duluin.ftth.helpdesk.application.port.inbound.TicketSummaryView
import com.duluin.ftth.helpdesk.application.port.inbound.TicketView
import com.duluin.ftth.helpdesk.application.port.outbound.TicketRepository
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketMessage
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import com.duluin.ftth.workorder.RaiseRepairCommand
import com.duluin.ftth.workorder.WorkorderApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Meja bantuan sisi OPERATOR: antrean tiket, utas percakapan, dan eskalasi ke work order.
 * Sisi pelanggan (portal) memakai agregat yang sama lewat [HelpdeskApiService] — jadi status
 * yang dibaca pelanggan tak mungkin menyimpang dari yang dikerjakan operator.
 */
@Service
@Transactional(readOnly = true)
class TicketService(
    private val tickets: TicketRepository,
    private val workorderApi: WorkorderApi,
    private val currentUser: CurrentUserProvider,
) : TicketQuery, ManageTicketUseCase {

    override fun search(filter: TicketFilter, pageRequest: PageRequest): Page<TicketView> =
        tickets.search(filter, pageRequest).map { it.toView() }

    override fun get(id: UUID): TicketDetail = load(id).toDetail()

    override fun summary(): TicketSummaryView {
        val byStatus = tickets.countByStatus()
        return TicketSummaryView(
            open = byStatus[TicketStatus.OPEN] ?: 0,
            inProgress = byStatus[TicketStatus.IN_PROGRESS] ?: 0,
            resolved = byStatus[TicketStatus.RESOLVED] ?: 0,
        )
    }

    @Transactional
    override fun reply(id: UUID, body: String): TicketDetail {
        val actor = currentUser.current()
        val ticket = load(id)
        ticket.replyByOperator(actor.userId, actor.name, body, Instant.now())
        return tickets.save(ticket).toDetail()
    }

    @Transactional
    override fun changeStatus(id: UUID, status: TicketStatus): TicketDetail {
        val actor = currentUser.current()
        val ticket = load(id)
        ticket.changeStatus(status, actor.userId, actor.name, Instant.now())
        return tickets.save(ticket).toDetail()
    }

    @Transactional
    override fun escalate(id: UUID, command: EscalateTicketCommand): TicketDetail {
        val actor = currentUser.current()
        val ticket = load(id)
        val workOrder = workorderApi.raiseRepair(
            RaiseRepairCommand(
                customerId = ticket.customerId,
                // Judul WO membawa kode tiketnya supaya teknisi & dispatcher bisa merunut balik
                // ke keluhan aslinya tanpa membuka helpdesk.
                title = "[${ticket.code}] ${ticket.subject}",
                description = listOfNotNull(ticket.description, command.note?.trim()?.ifEmpty { null })
                    .joinToString("\n\n"),
                priority = command.priority.name,
                scheduledAt = command.scheduledAt,
            ),
        )
        ticket.attachWorkOrder(workOrder.id, workOrder.code, actor.userId, actor.name, Instant.now())
        return tickets.save(ticket).toDetail()
    }

    private fun load(id: UUID): Ticket =
        tickets.findById(id) ?: throw NotFoundException("Tiket tidak ditemukan")

    private fun Ticket.toDetail() = TicketDetail(
        ticket = toView(),
        description = description,
        messages = tickets.messagesOf(id).map { it.toView() },
    )
}

internal fun Ticket.toView() = TicketView(
    id = id,
    code = code,
    customerId = customerId,
    customerName = customerName,
    category = category,
    subject = subject,
    status = status,
    workOrderId = workOrderId,
    workOrderCode = workOrderCode,
    openedAt = openedAt,
    lastActivityAt = lastActivityAt,
    resolvedAt = resolvedAt,
    closedAt = closedAt,
)

internal fun TicketMessage.toView() = TicketMessageView(
    author = author,
    authorName = authorName,
    body = body,
    at = at,
)
