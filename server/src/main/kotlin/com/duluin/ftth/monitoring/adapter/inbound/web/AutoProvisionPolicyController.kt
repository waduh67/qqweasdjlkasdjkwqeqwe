package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.AutoProvisionPolicyView
import com.duluin.ftth.monitoring.application.port.inbound.ManageAutoProvisionPolicyUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Setelan auto-provisioning zero-touch tenant: nyalakan untuk membiarkan sistem
 * menautkan sendiri ONU liar berkeyakinan HIGH tanpa menunggu operator. Dijaga
 * izin provisioning yang sama — operator yang boleh memprovisi boleh mengaturnya.
 */
@RestController
@RequestMapping("/api/monitoring/auto-provision-policy")
@Tag(name = "Monitoring — Provisioning")
@SecurityRequirement(name = "bearer-jwt")
class AutoProvisionPolicyController(
    private val useCase: ManageAutoProvisionPolicyUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('monitoring.provisioning.view')")
    @Operation(summary = "Setelan auto-provisioning zero-touch tenant")
    fun get(): AutoProvisionPolicyView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('monitoring.provisioning.manage')")
    @Operation(summary = "Nyalakan/matikan auto-provisioning zero-touch")
    fun update(@Valid @RequestBody request: AutoProvisionPolicyRequest): AutoProvisionPolicyView =
        useCase.setEnabled(request.enabled)
}

data class AutoProvisionPolicyRequest(
    @field:NotNull val enabled: Boolean,
)
