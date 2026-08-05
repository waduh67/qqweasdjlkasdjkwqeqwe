package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.portal.application.port.inbound.ManagePortalCredentialUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalCredentialProvisioned
import com.duluin.ftth.portal.application.port.inbound.PortalCredentialSummary
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Sisi OPERATOR untuk kredensial portal pelanggan — sengaja di path berawalan
 * `/api/portal-admin/` (rantai keamanan UTAMA operator), BUKAN `/api/portal/` (rantai
 * pelanggan). Guard izin RBAC `portal.credential.*`; segmen `portal-admin` tak tercocok
 * oleh matcher rantai portal.
 */
@RestController
@RequestMapping("/api/portal-admin/customers/{customerId}/credential")
@Tag(name = "Portal Admin")
@SecurityRequirement(name = "bearer-jwt")
class PortalCredentialAdminController(
    private val credentials: ManagePortalCredentialUseCase,
) {

    @GetMapping
    @PreAuthorize("@authz.can('portal.credential.view')")
    fun summary(@PathVariable customerId: UUID): PortalCredentialStatusResponse =
        PortalCredentialStatusResponse.from(credentials.summaryFor(customerId))

    @PostMapping
    @PreAuthorize("@authz.can('portal.credential.manage')")
    fun provision(
        @PathVariable customerId: UUID,
        @RequestBody request: ProvisionPortalCredentialRequest,
    ): PortalCredentialProvisionedResponse =
        PortalCredentialProvisionedResponse.from(
            credentials.provisionFor(customerId, request.login, request.password),
        )

    @PostMapping("/reset-password")
    @PreAuthorize("@authz.can('portal.credential.manage')")
    fun resetPassword(
        @PathVariable customerId: UUID,
        @RequestBody request: ResetPortalPasswordRequest,
    ): PortalCredentialProvisionedResponse =
        PortalCredentialProvisionedResponse.from(
            credentials.resetPassword(customerId, request.newPassword),
        )

    @PostMapping("/enable")
    @PreAuthorize("@authz.can('portal.credential.manage')")
    fun enable(@PathVariable customerId: UUID): PortalCredentialStatusResponse =
        PortalCredentialStatusResponse.from(credentials.setEnabled(customerId, enabled = true))

    @PostMapping("/disable")
    @PreAuthorize("@authz.can('portal.credential.manage')")
    fun disable(@PathVariable customerId: UUID): PortalCredentialStatusResponse =
        PortalCredentialStatusResponse.from(credentials.setEnabled(customerId, enabled = false))
}

/** Login opsional (default = kode pelanggan) & password opsional (default = generate sementara). */
data class ProvisionPortalCredentialRequest(
    val login: String? = null,
    val password: String? = null,
)

data class ResetPortalPasswordRequest(
    val newPassword: String? = null,
)

/** Status kredensial untuk panel operator; [provisioned] false bila belum pernah dibuat. */
data class PortalCredentialStatusResponse(
    val provisioned: Boolean,
    val customerId: UUID?,
    val login: String?,
    val active: Boolean,
) {
    companion object {
        fun from(summary: PortalCredentialSummary?) =
            if (summary == null) {
                PortalCredentialStatusResponse(provisioned = false, customerId = null, login = null, active = false)
            } else {
                PortalCredentialStatusResponse(
                    provisioned = true,
                    customerId = summary.customerId,
                    login = summary.login,
                    active = summary.active,
                )
            }
    }
}

/**
 * Hasil provisi/reset. [temporaryPassword] terisi HANYA saat server men-generate password —
 * operator wajib menyalin & membagikannya sekali ke pelanggan (tak bisa dilihat lagi).
 */
data class PortalCredentialProvisionedResponse(
    val customerId: UUID,
    val login: String,
    val active: Boolean,
    val temporaryPassword: String?,
) {
    companion object {
        fun from(provisioned: PortalCredentialProvisioned) = PortalCredentialProvisionedResponse(
            customerId = provisioned.customerId,
            login = provisioned.login,
            active = provisioned.active,
            temporaryPassword = provisioned.temporaryPassword,
        )
    }
}
