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
)

data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val user: AuthUserView,
)
