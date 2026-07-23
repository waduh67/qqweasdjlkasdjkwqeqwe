package com.duluin.ftth.incident.adapter.inbound.web

import com.duluin.ftth.incident.application.port.inbound.IncidentDetail
import com.duluin.ftth.incident.application.port.inbound.IncidentQuery
import com.duluin.ftth.incident.application.port.inbound.IncidentView
import com.duluin.ftth.incident.application.port.inbound.ManageIncidentUseCase
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Insiden hasil korelasi alarm: banjir alarm sejenis digabung jadi satu insiden
 * ber-akar-masalah, dengan lifecycle yang bisa dikelola operator.
 */
@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incident")
@SecurityRequirement(name = "bearer-jwt")
class IncidentController(
    private val query: IncidentQuery,
    private val manage: ManageIncidentUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('incident.ticket.view')")
    fun active(): List<IncidentView> = query.activeIncidents()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('incident.ticket.view')")
    fun detail(@PathVariable id: UUID): IncidentDetail = query.incident(id)

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("@authz.can('incident.ticket.update')")
    fun acknowledge(@PathVariable id: UUID): IncidentView = manage.acknowledge(id)

    @PostMapping("/{id}/resolve")
    @PreAuthorize("@authz.can('incident.ticket.close')")
    fun resolve(@PathVariable id: UUID): IncidentView = manage.resolve(id)
}
