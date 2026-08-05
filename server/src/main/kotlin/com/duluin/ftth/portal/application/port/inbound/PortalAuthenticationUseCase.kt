package com.duluin.ftth.portal.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Autentikasi realm PORTAL pelanggan. Berbeda dari `AuthenticationUseCase` operator:
 * login memakai SLUG TENANT eksplisit + login pelanggan (bukan email global) karena satu
 * login bisa kembar antar-tenant. Token yang diterbitkan ditandatangani secret terpisah.
 */
interface PortalAuthenticationUseCase {

    fun login(command: PortalLoginCommand): PortalAuthTokens

    fun refresh(refreshToken: String): PortalAuthTokens

    fun logout(refreshToken: String)
}

/** Slug tenant + login + password yang diketik pelanggan pada layar masuk portal. */
data class PortalLoginCommand(
    val tenantSlug: String,
    val login: String,
    val password: String,
)

/** Pasangan token + profil pelanggan — dikembalikan saat login & refresh berhasil. */
data class PortalAuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val customer: PortalProfileView,
)

/**
 * Profil pelanggan portal untuk respons auth (& endpoint /me). Sengaja ringkas: identitas
 * + status; data kaya (tagihan, koneksi, paket) dilayani query self-service terpisah.
 */
data class PortalProfileView(
    val customerId: UUID,
    val tenantId: UUID,
    val tenantSlug: String,
    val code: String,
    val name: String,
    val login: String,
    val phone: String?,
    val status: String,
)
