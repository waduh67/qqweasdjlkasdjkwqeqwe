package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.helpdesk.CustomerTicketDetail
import com.duluin.ftth.helpdesk.CustomerTicketView
import com.duluin.ftth.helpdesk.HelpdeskApi
import com.duluin.ftth.helpdesk.SubmitTicketCommand
import com.duluin.ftth.portal.security.CurrentPortalCustomer
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Meja bantuan sisi PELANGGAN: lapor gangguan, ikuti penanganannya, balas, dan tutup sendiri
 * bila sudah beres. Seperti endpoint portal lain, id pelanggan diambil dari principal — tak
 * pernah dari path/body — jadi laporan orang lain mustahil dibaca (dijawab 404, bukan 403).
 *
 * Portal hanya merangkai kontrak publik module helpdesk ([HelpdeskApi]); tabel tiket tetap
 * milik module helpdesk sepenuhnya.
 */
@RestController
@RequestMapping("/api/portal/me/tickets")
@Tag(name = "Portal")
class PortalTicketController(
    private val helpdesk: HelpdeskApi,
    private val currentPortalCustomer: CurrentPortalCustomer,
) {

    @GetMapping
    fun list(): List<CustomerTicketView> = helpdesk.findTicketsOf(currentCustomerId())

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID): CustomerTicketDetail =
        helpdesk.findTicketOf(currentCustomerId(), id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@Valid @RequestBody request: SubmitTicketRequest): CustomerTicketDetail =
        helpdesk.submit(
            SubmitTicketCommand(
                customerId = currentCustomerId(),
                category = request.category!!,
                subject = request.subject!!,
                description = request.description!!,
            ),
        )

    @PostMapping("/{id}/replies")
    fun reply(@PathVariable id: UUID, @Valid @RequestBody request: PortalTicketReplyRequest): CustomerTicketDetail =
        helpdesk.replyAsCustomer(currentCustomerId(), id, request.body!!)

    @PostMapping("/{id}/close")
    fun close(@PathVariable id: UUID): CustomerTicketDetail =
        helpdesk.closeAsCustomer(currentCustomerId(), id)

    private fun currentCustomerId() = currentPortalCustomer.current().customerId
}

/** [category] = nama `TicketCategory` (mis. `KONEKSI_PUTUS`); nilai tak dikenal ditolak 400. */
data class SubmitTicketRequest(
    @field:NotBlank val category: String? = null,
    @field:NotBlank @field:Size(max = 150) val subject: String? = null,
    @field:NotBlank @field:Size(max = 2000) val description: String? = null,
)

data class PortalTicketReplyRequest(
    @field:NotBlank @field:Size(max = 2000) val body: String? = null,
)
