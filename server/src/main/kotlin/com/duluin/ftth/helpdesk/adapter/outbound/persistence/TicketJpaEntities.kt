package com.duluin.ftth.helpdesk.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.helpdesk.domain.model.TicketAuthor
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketPriority
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "helpdesk_ticket")
class TicketJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 20, updatable = false)
    var code: String,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    // Salinan nama saat tiket dibuka: antrean operator terbaca tanpa join lintas-module,
    // dan riwayat tetap masuk akal walau pelanggan kemudian berganti nama.
    @Column(name = "customer_name", nullable = false, length = 150, updatable = false)
    var customerName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var category: TicketCategory,

    @Column(nullable = false, length = 150, updatable = false)
    var subject: String,

    @Column(nullable = false, length = 2000, updatable = false)
    var description: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TicketStatus,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var priority: TicketPriority,

    // Operator pemegang tiket; id lintas-module tanpa FK, namanya disalin agar antrean
    // terbaca tanpa resolusi ke module iam per baris.
    @Column(name = "assignee_id")
    var assigneeId: UUID?,

    @Column(name = "assignee_name", length = 150)
    var assigneeName: String?,

    @Column(name = "work_order_id")
    var workOrderId: UUID?,

    @Column(name = "work_order_code", length = 20)
    var workOrderCode: String?,

    @Column(name = "opened_at", nullable = false, updatable = false)
    var openedAt: Instant,

    @Column(name = "last_activity_at", nullable = false)
    var lastActivityAt: Instant,

    @Column(name = "first_response_at")
    var firstResponseAt: Instant?,

    /** Null = bola tak sedang di tangan operator (sudah dibalas / tiket tertutup). */
    @Column(name = "response_due_at")
    var responseDueAt: Instant?,

    @Column(name = "resolution_due_at", nullable = false)
    var resolutionDueAt: Instant,

    @Column(name = "sla_alerted_at")
    var slaAlertedAt: Instant?,

    @Column(name = "resolved_at")
    var resolvedAt: Instant?,

    @Column(name = "closed_at")
    var closedAt: Instant?,
) : TenantAwareJpaEntity(id)

/** Satu pesan utas. Append-only: pesan yang sudah terkirim tak pernah disunting. */
@Entity
@Table(name = "helpdesk_ticket_message")
class TicketMessageJpaEntity(
    id: UUID,

    @Column(name = "ticket_id", nullable = false, updatable = false)
    var ticketId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var author: TicketAuthor,

    @Column(name = "author_id", updatable = false)
    var authorId: UUID?,

    @Column(name = "author_name", nullable = false, length = 150, updatable = false)
    var authorName: String,

    @Column(nullable = false, length = 2000, updatable = false)
    var body: String,

    @Column(nullable = false, updatable = false)
    var at: Instant,
) : TenantAwareJpaEntity(id)
