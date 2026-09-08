package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventory
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventoryUseCase
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Monitoring — Inventori ONU")
@SecurityRequirement(name = "bearer-jwt")
class OltOnuInventoryController(
    private val useCase: OltOnuInventoryUseCase,
) {
    @GetMapping("/{oltId}/onus")
    @PreAuthorize("@authz.can('network.olt.view') and @authz.can('monitoring.provisioning.view')")
    @Operation(summary = "Baca inventori ONU langsung dari snapshot SNMP OLT milik tenant")
    fun read(@PathVariable oltId: UUID): OltOnuInventory = useCase.read(oltId)

    @ExceptionHandler(SnmpProbeFailure::class)
    fun handleProbeFailure(ex: SnmpProbeFailure): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.message ?: "Inventori SNMP tidak dapat dibaca")
}
