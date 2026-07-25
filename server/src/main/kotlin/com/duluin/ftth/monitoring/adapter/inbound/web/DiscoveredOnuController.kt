package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.DiscoveredOnuView
import com.duluin.ftth.monitoring.application.port.inbound.ManageDiscoveredOnuUseCase
import com.duluin.ftth.monitoring.application.port.inbound.ProvisionDiscoveredOnuCommand
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Kotak masuk auto-provisioning: ONU yang terlihat OLT tapi belum terdaftar,
 * dituntaskan operator menjadi pelanggan terpasang tanpa mengetik ulang serial.
 */
@RestController
@RequestMapping("/api/monitoring/discovered-onus")
@Tag(name = "Monitoring — Provisioning")
@SecurityRequirement(name = "bearer-jwt")
class DiscoveredOnuController(
    private val useCase: ManageDiscoveredOnuUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('monitoring.provisioning.view')")
    @Operation(summary = "Daftar ONU terdeteksi; default yang masih menunggu (DISCOVERED)")
    fun list(@RequestParam(required = false) state: DiscoveredOnuState?): List<DiscoveredOnuView> =
        useCase.list(state)

    @PostMapping("/{id}/provision")
    @PreAuthorize("@authz.can('monitoring.provisioning.manage')")
    @Operation(summary = "Tuntaskan: daftarkan ONU untuk pelanggan lalu pasang ke port ODP")
    fun provision(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ProvisionDiscoveredOnuRequest,
    ): DiscoveredOnuView = useCase.provision(id, request.toCommand())

    @PostMapping("/{id}/ignore")
    @PreAuthorize("@authz.can('monitoring.provisioning.manage')")
    fun ignore(@PathVariable id: UUID): DiscoveredOnuView = useCase.ignore(id)
}

data class ProvisionDiscoveredOnuRequest(
    @field:NotNull val customerId: UUID,
    @field:NotNull val odpId: UUID,
    @field:Min(1) val portNumber: Int,
    val installRxPowerDbm: Double?,
) {
    fun toCommand() = ProvisionDiscoveredOnuCommand(
        customerId = customerId,
        odpId = odpId,
        portNumber = portNumber,
        installRxPowerDbm = installRxPowerDbm,
    )
}
