package com.duluin.ftth.helpdesk.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.helpdesk.application.port.inbound.EscalateTicketCommand
import com.duluin.ftth.helpdesk.application.port.inbound.ManageTicketUseCase
import com.duluin.ftth.helpdesk.application.port.inbound.TicketDetail
import com.duluin.ftth.helpdesk.application.port.inbound.TicketFilter
import com.duluin.ftth.helpdesk.application.port.inbound.TicketQuery
import com.duluin.ftth.helpdesk.application.port.inbound.TicketSummaryView
import com.duluin.ftth.helpdesk.application.port.inbound.TicketView
import com.duluin.ftth.helpdesk.domain.model.Ticket
import com.duluin.ftth.helpdesk.domain.model.TicketCategory
import com.duluin.ftth.helpdesk.domain.model.TicketPriority
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Meja bantuan sisi OPERATOR: antrean keluhan yang masuk dari portal pelanggan, utas
 * balasannya, dan eskalasi ke work order bila butuh kunjungan teknisi. Pintu pelanggan
 * ada di realm portal ([com.duluin.ftth.portal.adapter.inbound.web.PortalTicketController]).
 */
@RestController
@RequestMapping("/api/helpdesk/tickets")
@Tag(name = "Helpdesk")
@SecurityRequirement(name = "bearer-jwt")
class TicketController(
    private val query: TicketQuery,
    private val manage: ManageTicketUseCase,
) {

    @GetMapping
    @PreAuthorize("@authz.can('helpdesk.ticket.view')")
    @Suppress("LongParameterList")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: TicketStatus?,
        @RequestParam(required = false) category: TicketCategory?,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<TicketView> = PageResponse.from(
        this.query.search(
            TicketFilter(query = query, status = status, category = category, customerId = customerId),
            // Yang paling baru bergerak di atas: antrean bantuan dibaca dari percakapan terakhir.
            PageRequest(page, size, sort = "lastActivityAt", descending = true),
        ),
    )

    @GetMapping("/summary")
    @PreAuthorize("@authz.can('helpdesk.ticket.view')")
    fun summary(): TicketSummaryView = query.summary()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('helpdesk.ticket.view')")
    fun detail(@PathVariable id: UUID): TicketDetail = query.get(id)

    @PostMapping("/{id}/replies")
    @PreAuthorize("@authz.can('helpdesk.ticket.reply')")
    fun reply(@PathVariable id: UUID, @Valid @RequestBody request: TicketReplyRequest): TicketDetail =
        manage.reply(id, request.body!!)

    @PostMapping("/{id}/status")
    @PreAuthorize("@authz.can('helpdesk.ticket.manage')")
    fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: TicketStatusRequest): TicketDetail =
        manage.changeStatus(id, request.status!!)

    @PostMapping("/{id}/escalate")
    @PreAuthorize("@authz.can('helpdesk.ticket.manage')")
    fun escalate(@PathVariable id: UUID, @Valid @RequestBody request: EscalateRequest): TicketDetail =
        manage.escalate(
            id,
            EscalateTicketCommand(
                priority = request.priority ?: TicketPriority.NORMAL,
                scheduledAt = request.scheduledAt,
                note = request.note,
            ),
        )
}

data class TicketReplyRequest(
    @field:NotBlank @field:Size(max = Ticket.MAX_BODY) val body: String? = null,
)

data class TicketStatusRequest(
    @field:NotNull val status: TicketStatus? = null,
)

/** Prioritas kosong = NORMAL; [note] jadi konteks tambahan di deskripsi work order. */
data class EscalateRequest(
    val priority: TicketPriority? = null,
    val scheduledAt: Instant? = null,
    @field:Size(max = 500) val note: String? = null,
)
