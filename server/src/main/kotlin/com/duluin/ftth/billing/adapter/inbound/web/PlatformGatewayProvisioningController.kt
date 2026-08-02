package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ProvisionSubAccountCommand
import com.duluin.ftth.billing.application.port.inbound.ProvisionSubAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.SubAccountProvisionResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Aksi platform-admin untuk menyiapkan sub-account Xendit (mode PLATFORM/xenPlatform) atas nama
 * tenant. Dijaga izin platform `billing.gateway.provision` (tak tenant-assignable) —
 * memakai kredensial MASTER agregator, bukan setelan self-service tenant.
 */
@RestController
@RequestMapping("/api/billing/platform/gateway")
@Tag(name = "Billing — provisioning gateway platform")
@SecurityRequirement(name = "bearer-jwt")
class PlatformGatewayProvisioningController(
    private val useCase: ProvisionSubAccountUseCase,
) {
    @PostMapping("/{tenantId}/xendit-subaccount")
    @PreAuthorize("@authz.can('billing.gateway.provision')")
    @Operation(summary = "Buat sub-account Xendit (PLATFORM) & kunci gateway tenant ke mode agregator")
    fun provisionXendit(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: ProvisionXenditSubAccountRequest,
    ): SubAccountProvisionResult =
        useCase.provisionXendit(
            ProvisionSubAccountCommand(
                tenantId = tenantId,
                email = request.email,
                businessName = request.businessName,
            ),
        )
}

/**
 * [email] alamat sub-account (WAJIB, unik di Xendit); [businessName] nama bisnis publik
 * (kosong = pakai nama tenant).
 */
data class ProvisionXenditSubAccountRequest(
    @field:NotBlank @field:Email @field:Size(max = 255) val email: String,
    @field:Size(max = 255) val businessName: String? = null,
)
