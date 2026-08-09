package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.portal.application.port.inbound.ManagePortalCredentialUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalAuthenticationUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalLoginCommand
import com.duluin.ftth.portal.application.port.inbound.PortalLoginResult
import com.duluin.ftth.portal.application.port.inbound.PortalPasswordRecoveryUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalProfileView
import com.duluin.ftth.portal.application.port.inbound.PortalResetPasswordCommand
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
 * `login`, `refresh`, dan pemulihan password terbuka; sisanya butuh token portal.
 *
 * Pelanggan masuk cukup dengan SATU identitas — email, nomor HP, atau username — tanpa
 * menyebut ISP-nya. Kode ISP sengaja tak lagi diminta: itu hal yang tak pernah dihafal
 * pelanggan, dan sebetulnya bisa disimpulkan sendiri oleh server dari identitasnya.
 */
@RestController
@RequestMapping("/api/portal")
@Tag(name = "Portal")
class PortalAuthController(
    private val authentication: PortalAuthenticationUseCase,
    private val passwordRecovery: PortalPasswordRecoveryUseCase,
    private val credentials: ManagePortalCredentialUseCase,
    private val currentPortalCustomer: CurrentPortalCustomer,
) {

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody request: PortalLoginRequest): PortalLoginResponse =
        PortalLoginResponse.from(
            authentication.login(PortalLoginCommand(request.identifier, request.password, request.tenant)),
        )

    /**
     * Minta kode pemulihan. SELALU 204, apa pun yang terjadi di dalam — jawaban yang
     * membedakan "identitas dikenal" dari "tidak" akan menjadikan endpoint ini alat
     * memetakan pelanggan sebuah ISP satu per satu.
     */
    @PostMapping("/auth/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun forgotPassword(@Valid @RequestBody request: PortalForgotPasswordRequest) =
        passwordRecovery.requestReset(request.identifier, request.tenant)

    /** Tukar kode dengan password baru. Di sini kegagalan dilaporkan apa adanya (400). */
    @PostMapping("/auth/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetPassword(@Valid @RequestBody request: PortalResetPasswordRequest) =
        passwordRecovery.completeReset(
            PortalResetPasswordCommand(request.identifier, request.code, request.newPassword),
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

/**
 * [identifier] = email / nomor HP / username, apa pun yang diingat pelanggan.
 * [tenant] opsional dan hanya terisi bila pelanggan datang lewat tautan ber-ISP atau baru
 * saja memilih ISP di layar lanjutan — bukan sesuatu yang perlu ia ketik sendiri.
 */
data class PortalLoginRequest(
    @field:NotBlank val identifier: String,
    @field:NotBlank val password: String,
    val tenant: String? = null,
)

data class PortalForgotPasswordRequest(
    @field:NotBlank val identifier: String,
    val tenant: String? = null,
)

data class PortalResetPasswordRequest(
    @field:NotBlank val identifier: String,
    @field:NotBlank val code: String,
    @field:NotBlank val newPassword: String,
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

/**
 * Hasil satu percobaan masuk: langsung diterima, atau — bila identitas yang sama ternyata
 * dipakai di lebih dari satu ISP — daftar ISP untuk dipilih.
 *
 * [choices] hanya pernah terisi setelah password TERBUKTI benar di ISP-ISP itu. Tanpa syarat
 * itu, layar masuk berubah jadi alat intip: mengetik nomor HP orang lain sudah cukup untuk
 * tahu ia pelanggan siapa.
 */
data class PortalLoginResponse(
    /** `AUTHENTICATED` atau `CHOOSE_TENANT`. */
    val status: String,
    val tokens: PortalTokenResponse?,
    val choices: List<PortalTenantChoiceResponse>,
) {
    companion object {
        fun from(result: PortalLoginResult): PortalLoginResponse = when (result) {
            is PortalLoginResult.Authenticated ->
                PortalLoginResponse("AUTHENTICATED", PortalTokenResponse.from(result.tokens), emptyList())

            is PortalLoginResult.ChooseTenant -> PortalLoginResponse(
                status = "CHOOSE_TENANT",
                tokens = null,
                choices = result.choices.map { PortalTenantChoiceResponse(it.tenantSlug, it.tenantName) },
            )
        }
    }
}

data class PortalTenantChoiceResponse(
    val tenantSlug: String,
    val tenantName: String,
)

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
