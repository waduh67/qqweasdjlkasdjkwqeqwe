package com.duluin.ftth.helpdesk.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.helpdesk.application.port.inbound.TicketFilter
import com.duluin.ftth.helpdesk.application.port.outbound.TicketRepository
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketMessage
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TicketPersistenceAdapter(
    private val jpa: TicketJpaRepository,
    private val messageJpa: TicketMessageJpaRepository,
) : TicketRepository {

    override fun save(ticket: Ticket): Ticket {
        val entity = jpa.findById(ticket.id).orElse(null)?.apply {
            status = ticket.status
            workOrderId = ticket.workOrderId
            workOrderCode = ticket.workOrderCode
            lastActivityAt = ticket.lastActivityAt
            resolvedAt = ticket.resolvedAt
            closedAt = ticket.closedAt
        } ?: TicketJpaEntity(
            id = ticket.id,
            code = ticket.code,
            customerId = ticket.customerId,
            customerName = ticket.customerName,
            category = ticket.category,
            subject = ticket.subject,
            description = ticket.description,
            status = ticket.status,
            workOrderId = ticket.workOrderId,
            workOrderCode = ticket.workOrderCode,
            openedAt = ticket.openedAt,
            lastActivityAt = ticket.lastActivityAt,
            resolvedAt = ticket.resolvedAt,
            closedAt = ticket.closedAt,
        )
        jpa.save(entity)

        // Pesan yang tertunda di agregat ditulis bersama tiketnya, lalu dikosongkan
        // supaya pemanggil yang menyimpan dua kali tak menggandakan utas.
        ticket.pendingMessages().forEach { m ->
            messageJpa.save(
                TicketMessageJpaEntity(
                    id = m.id,
                    ticketId = m.ticketId,
                    author = m.author,
                    authorId = m.authorId,
                    authorName = m.authorName,
                    body = m.body,
                    at = m.at,
                ),
            )
        }
        ticket.clearPendingMessages()
        return ticket
    }

    override fun findById(id: UUID): Ticket? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByCustomer(customerId: UUID): List<Ticket> =
        jpa.findByCustomerIdOrderByOpenedAtDesc(customerId).map { it.toDomain() }

    override fun search(filter: TicketFilter, pageRequest: PageRequest): Page<Ticket> {
        val spec = matchesText(filter.query)
            .and(hasStatus(filter.status))
            .and(hasCategory(filter.category))
            .and(hasCustomer(filter.customerId))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun messagesOf(ticketId: UUID): List<TicketMessage> =
        messageJpa.findByTicketIdOrderByAt(ticketId).map { it.toDomain() }

    override fun countByStatus(): Map<TicketStatus, Long> =
        jpa.countGroupedByStatus().associate { it.status to it.total }

    override fun countOpenOf(customerId: UUID): Long =
        jpa.countByCustomerIdAndStatusNot(customerId, TicketStatus.CLOSED)

    private fun matchesText(query: String?) = Specification<TicketJpaEntity> { root, _, cb ->
        val needle = query?.trim()?.lowercase().orEmpty()
        if (needle.isEmpty()) {
            cb.conjunction()
        } else {
            val pattern = "%$needle%"
            cb.or(
                cb.like(cb.lower(root.get("code")), pattern),
                cb.like(cb.lower(root.get("subject")), pattern),
                cb.like(cb.lower(root.get("customerName")), pattern),
            )
        }
    }

    private fun hasStatus(status: TicketStatus?) = Specification<TicketJpaEntity> { root, _, cb ->
        if (status == null) cb.conjunction() else cb.equal(root.get<TicketStatus>("status"), status)
    }

    private fun hasCategory(category: TicketCategory?) = Specification<TicketJpaEntity> { root, _, cb ->
        if (category == null) cb.conjunction() else cb.equal(root.get<TicketCategory>("category"), category)
    }

    private fun hasCustomer(customerId: UUID?) = Specification<TicketJpaEntity> { root, _, cb ->
        if (customerId == null) cb.conjunction() else cb.equal(root.get<UUID>("customerId"), customerId)
    }
}

private fun TicketJpaEntity.toDomain() = Ticket.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    customerId = customerId,
    customerName = customerName,
    category = category,
    subject = subject,
    description = description,
    status = status,
    workOrderId = workOrderId,
    workOrderCode = workOrderCode,
    openedAt = openedAt,
    lastActivityAt = lastActivityAt,
    resolvedAt = resolvedAt,
    closedAt = closedAt,
)

private fun TicketMessageJpaEntity.toDomain() = TicketMessage.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    ticketId = ticketId,
    author = author,
    authorId = authorId,
    authorName = authorName,
    body = body,
    at = at,
)
