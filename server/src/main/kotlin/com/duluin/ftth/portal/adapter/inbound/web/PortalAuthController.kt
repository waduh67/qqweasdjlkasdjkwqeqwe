package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.portal.application.port.inbound.ManagePortalCredentialUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalAuthenticationUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalLoginCommand
import com.duluin.ftth.portal.application.port.inbound.PortalProfileView
import com.duluin.ftth.portal.security.CurrentPortalCustomer
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Endpoint realm PORTAL pelanggan (path berawalan `/api/portal/`, rantai keamanan
 * [com.duluin.ftth.portal.adapter.inbound.security.PortalSecurityConfig]).
 *
 * `login`/`refresh` terbuka; sisanya butuh token portal. Login memakai SLUG tenant + login
 * pelanggan (bukan email global) karena login boleh kembar antar-tenant.
 */
@RestController
@RequestMapping("/api/portal")
@Tag(name = "Portal")
class PortalAuthController(
    private val authentication: PortalAuthenticationUseCase,
    private val credentials: ManagePortalCredentialUseCase,
    private val currentPortalCustomer: CurrentPortalCustomer,
) {

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody request: PortalLoginRequest): PortalTokenResponse =
        PortalTokenResponse.from(
            authentication.login(PortalLoginCommand(request.tenant, request.login, request.password)),
        )

    @PostMapping("/auth/refresh")
    fun refresh(@Valid @RequestBody request: PortalRefreshRequest): PortalTokenResponse =
        PortalTokenResponse.from(authentication.refresh(request.refreshToken))

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody request: PortalRefreshRequest) =
        authentication.logout(request.refreshToken)

    /** Identitas pelanggan yang sedang login — cukup untuk header portal; data kaya via query self-service. */
    @GetMapping("/me")
    fun me(): PortalMeResponse {
        val customer = currentPortalCustomer.current()
        return PortalMeResponse(customer.customerId, customer.tenantId, customer.login, customer.name)
    }

    /** Ganti password mandiri; seluruh sesi berakhir sehingga pelanggan login ulang. */
    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@Valid @RequestBody request: PortalChangePasswordRequest) =
        credentials.changeOwnPassword(request.currentPassword, request.newPassword)
}

data class PortalLoginRequest(
    @field:NotBlank val tenant: String,
    @field:NotBlank val login: String,
    @field:NotBlank val password: String,
)

data class PortalRefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class PortalChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank val newPassword: String,
)

data class PortalMeResponse(
    val customerId: UUID,
    val tenantId: UUID,
    val login: String,
    val name: String,
)

data class PortalTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val customer: PortalProfileResponse,
) {
    companion object {
        fun from(tokens: PortalAuthTokens) = PortalTokenResponse(
            accessToken = tokens.accessToken,
            tokenType = "Bearer",
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
            customer = PortalProfileResponse.from(tokens.customer),
        )
    }
}

data class PortalProfileResponse(
    val customerId: UUID,
    val tenantId: UUID,
    val tenantSlug: String,
    val code: String,
    val name: String,
    val login: String,
    val phone: String?,
    val status: String,
) {
    companion object {
        fun from(view: PortalProfileView) = PortalProfileResponse(
            customerId = view.customerId,
            tenantId = view.tenantId,
            tenantSlug = view.tenantSlug,
            code = view.code,
            name = view.name,
            login = view.login,
            phone = view.phone,
            status = view.status,
        )
    }
}
