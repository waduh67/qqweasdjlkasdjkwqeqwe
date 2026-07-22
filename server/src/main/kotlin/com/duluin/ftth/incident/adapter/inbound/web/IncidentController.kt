package com.duluin.ftth.incident.adapter.inbound.web

import com.duluin.ftth.incident.application.port.inbound.IncidentQuery
import com.duluin.ftth.incident.application.port.inbound.IncidentView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Insiden hasil korelasi alarm hidup. Dihitung on-demand (belum dipersistensi):
 * jendela ke keadaan sekarang, bukan tiket dengan lifecycle — itu langkah
 * berikutnya (Phase 3, slice 1b).
 */
@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incident")
@SecurityRequirement(name = "bearer-jwt")
class IncidentController(
    private val incidents: IncidentQuery,
) {
    @GetMapping
    @PreAuthorize("@authz.can('incident.ticket.view')")
    fun active(): List<IncidentView> = incidents.activeIncidents()
}
