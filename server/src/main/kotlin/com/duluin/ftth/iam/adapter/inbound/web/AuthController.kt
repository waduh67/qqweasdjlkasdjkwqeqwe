package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.iam.application.port.inbound.AuthTokens
import com.duluin.ftth.iam.application.port.inbound.AuthUserView
import com.duluin.ftth.iam.application.port.inbound.AuthenticationUseCase
import com.duluin.ftth.iam.application.port.inbound.LoginCommand
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
class AuthController(
    private val authentication: AuthenticationUseCase,
    private val throttle: AttemptThrottle,
) {
    /**
     * Rem-nya dipasang di adapter, bukan di use case: yang dibatasi adalah PERMINTAAN HTTP
     * (siapa dari IP mana), sedangkan `AuthenticationUseCase` sengaja tak tahu-menahu soal
     * HTTP. Identitas dikecilkan huruf lebih dulu agar kapitalisasi tak bisa dipakai
     * memperbanyak jatah tebakan.
     */
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, http: HttpServletRequest): TokenResponse =
        throttle.guardLogin("op", http.remoteAddr, request.email.trim().lowercase()) {
            TokenResponse.from(
                authentication.login(LoginCommand(request.email, request.password, request.otpCode)),
            )
        }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse =
        TokenResponse.from(authentication.refresh(request.refreshToken))

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody request: RefreshRequest) =
        authentication.logout(request.refreshToken)
}

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    /** Diisi hanya pada percobaan kedua, setelah server menjawab `code=TWO_FACTOR_REQUIRED`. */
    val otpCode: String? = null,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val user: ProfileResponse,
) {
    companion object {
        fun from(tokens: AuthTokens) = TokenResponse(
            accessToken = tokens.accessToken,
            tokenType = "Bearer",
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
            user = ProfileResponse.from(tokens.user),
        )
    }
}

/** Profil pengguna — dipakai response login & endpoint /me. */
data class ProfileResponse(
    val id: UUID,
    val email: String,
    val name: String,
    val tenantId: UUID,
    val tenantSlug: String,
    val platformAdmin: Boolean,
    val roleIds: List<UUID>,
    val permissions: List<String>,
    val areaIds: List<UUID>,
    val twoFactorEnabled: Boolean,
) {
    companion object {
        fun from(view: AuthUserView) = ProfileResponse(
            id = view.id,
            email = view.email,
            name = view.name,
            tenantId = view.tenantId,
            tenantSlug = view.tenantSlug,
            platformAdmin = view.platformAdmin,
            roleIds = view.roleIds,
            permissions = view.permissions,
            areaIds = view.areaIds,
            twoFactorEnabled = view.twoFactorEnabled,
        )
    }
}
