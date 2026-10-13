package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.DiscoverPonPortsUseCase
import com.duluin.ftth.monitoring.application.port.inbound.PonPortDiscovery
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/monitoring/olts")
class PonPortDiscoveryController(private val discovery: DiscoverPonPortsUseCase) {
    @GetMapping("/{id}/pon-ports")
    @PreAuthorize("@authz.can('network.olt.view') and @authz.can('monitoring.provisioning.view')")
    fun discover(@PathVariable id: UUID): PonPortDiscovery = discovery.discover(id)

    @ExceptionHandler(SnmpProbeFailure::class)
    fun failed(ex: SnmpProbeFailure): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.message ?: "Port PON belum dapat dibaca.")
}
