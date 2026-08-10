package com.duluin.ftth.helpdesk.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
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
import com.duluin.ftth.helpdesk.domain.model.TicketPriority
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import com.duluin.ftth.iam.IamApi
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
    private val iamApi: IamApi,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : TicketQuery, ManageTicketUseCase {

    override fun search(filter: TicketFilter, pageRequest: PageRequest): Page<TicketView> {
        // Satu penanda waktu untuk seluruh halaman: kalau tiap baris memanggil jamnya sendiri,
        // dua tiket dengan tenggat sama bisa tampil beda status di layar yang sama.
        val now = Instant.now()
        return tickets.search(filter, pageRequest).map { it.toView(now) }
    }

    override fun get(id: UUID): TicketDetail = load(id).toDetail()

    override fun summary(): TicketSummaryView {
        val byStatus = tickets.countByStatus()
        return TicketSummaryView(
            open = byStatus[TicketStatus.OPEN] ?: 0,
            inProgress = byStatus[TicketStatus.IN_PROGRESS] ?: 0,
            resolved = byStatus[TicketStatus.RESOLVED] ?: 0,
            unassigned = tickets.countUnassigned(),
            overdue = tickets.countOverdue(Instant.now()),
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

    /**
     * Penugasan divalidasi ke module iam: id sembarang akan menempelkan nama kosong di antrean,
     * dan pengguna nonaktif akan membuat tiket "punya pemilik" yang tak pernah dibuka siapa pun.
     */
    @Transactional
    override fun assign(id: UUID, userId: UUID?): TicketDetail {
        val ticket = load(id)
        val assignee = userId?.let {
            iamApi.findUser(it)?.takeIf { user -> user.active }
                ?: throw ValidationException("Operator tujuan tidak ditemukan atau sudah nonaktif")
        }
        ticket.assignTo(assignee?.id, assignee?.name, Instant.now())
        val saved = tickets.save(ticket)
        auditor.record(
            action = if (assignee == null) "helpdesk.ticket.unassign" else "helpdesk.ticket.assign",
            entityType = "HelpdeskTicket",
            entityId = ticket.id,
            tenantId = ticket.tenantId,
            detail = mapOf("code" to ticket.code, "assigneeId" to assignee?.id, "assignee" to assignee?.name),
        )
        return saved.toDetail()
    }

    @Transactional
    override fun changePriority(id: UUID, priority: TicketPriority): TicketDetail {
        val ticket = load(id)
        val sebelumnya = ticket.priority
        ticket.changePriority(priority, Instant.now())
        val saved = tickets.save(ticket)
        auditor.record(
            action = "helpdesk.ticket.priority",
            entityType = "HelpdeskTicket",
            entityId = ticket.id,
            tenantId = ticket.tenantId,
            detail = mapOf("code" to ticket.code, "dari" to sebelumnya.name, "ke" to priority.name),
        )
        return saved.toDetail()
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
                // Tanpa pilihan eksplisit, WO mewarisi prioritas tiketnya.
                priority = (command.priority ?: ticket.priority).name,
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

internal fun Ticket.toView(now: Instant = Instant.now()) = TicketView(
    id = id,
    code = code,
    customerId = customerId,
    customerName = customerName,
    category = category,
    subject = subject,
    status = status,
    priority = priority,
    assigneeId = assigneeId,
    assigneeName = assigneeName,
    workOrderId = workOrderId,
    workOrderCode = workOrderCode,
    openedAt = openedAt,
    lastActivityAt = lastActivityAt,
    firstResponseAt = firstResponseAt,
    responseDueAt = responseDueAt,
    resolutionDueAt = resolutionDueAt,
    responseOverdue = responseOverdue(now),
    resolutionOverdue = resolutionOverdue(now),
    resolvedAt = resolvedAt,
    closedAt = closedAt,
)

internal fun TicketMessage.toView() = TicketMessageView(
    author = author,
    authorName = authorName,
    body = body,
    at = at,
)
