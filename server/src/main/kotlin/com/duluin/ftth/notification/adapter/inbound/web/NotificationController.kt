package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.notification.application.port.inbound.BroadcastDetail
import com.duluin.ftth.notification.application.port.inbound.BroadcastQuery
import com.duluin.ftth.notification.application.port.inbound.BroadcastView
import com.duluin.ftth.notification.application.port.inbound.SendBroadcastUseCase
import com.duluin.ftth.notification.application.port.inbound.SendIncidentBroadcastCommand
import com.duluin.ftth.notification.domain.model.NotificationChannel
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import java.util.UUID

/**
 * Broadcast pemberitahuan gangguan: kirim ke seluruh pelanggan terdampak sebuah
 * insiden ("layanan Anda sedang terganggu, tim kami menanganinya") sebelum mereka
 * komplain, lalu simpan riwayatnya.
 */
@RestController
@RequestMapping("/api/notifications/broadcasts")
@Tag(name = "Notification")
@SecurityRequirement(name = "bearer-jwt")
class NotificationController(
    private val send: SendBroadcastUseCase,
    private val query: BroadcastQuery,
) {
    @GetMapping
    @PreAuthorize("@authz.can('notification.broadcast.view')")
    fun history(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<BroadcastView> = PageResponse.from(query.history(PageRequest(page, size)))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('notification.broadcast.view')")
    fun detail(@PathVariable id: UUID): BroadcastDetail = query.detail(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('notification.broadcast.send')")
    fun broadcast(@Valid @RequestBody request: BroadcastRequest): BroadcastView =
        send.broadcastForIncident(
            SendIncidentBroadcastCommand(
                incidentId = request.incidentId,
                channel = request.channel ?: NotificationChannel.WHATSAPP,
                message = request.message,
            ),
        )
}

data class BroadcastRequest(
    val incidentId: UUID,
    val channel: NotificationChannel? = null,
    @field:NotBlank @field:Size(max = 2000) val message: String,
)
