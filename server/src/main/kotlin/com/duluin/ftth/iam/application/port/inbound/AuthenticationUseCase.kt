package com.duluin.ftth.iam.application.port.inbound

import java.time.Instant

/** Alur autentikasi: login, rotasi refresh-token, dan logout. */
interface AuthenticationUseCase {

    fun login(command: LoginCommand): AuthTokens

    fun refresh(refreshToken: String): AuthTokens

    fun logout(refreshToken: String)
}

data class LoginCommand(
    val email: String,
    val password: String,
    /**
     * Kode faktor kedua: 6 digit dari aplikasi autentikator ATAU satu kode pemulihan.
     * Null pada percobaan pertama — klien belum tahu akun ini memakai 2FA sampai server
     * memintanya lewat `TwoFactorRequiredException`.
     */
    val otpCode: String? = null,
)

data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val user: AuthUserView,
)
