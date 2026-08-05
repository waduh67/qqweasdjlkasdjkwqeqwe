package com.duluin.ftth.portal.application.port.outbound

import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.domain.model.PortalRefreshToken
import java.time.Instant
import java.util.UUID

/**
 * Port persistence kredensial portal. Tenant-aware (@TenantId + RLS) → pencarian
 * ter-scope tenant aktif otomatis; login yang sama di tenant berbeda tak saling tabrak.
 */
interface PortalCredentialRepository {

    fun save(credential: PortalCredential): PortalCredential

    fun findByLogin(login: String): PortalCredential?

    fun findByCustomerId(customerId: UUID): PortalCredential?
}

/**
 * Port persistence refresh-token portal. SENGAJA bukan tenant-aware: lookup by hash
 * terjadi sebelum tenant context terbentuk (saat refresh/logout).
 */
interface PortalRefreshTokenRepository {

    fun save(token: PortalRefreshToken): PortalRefreshToken

    fun findByTokenHash(tokenHash: String): PortalRefreshToken?

    fun revokeAllForCustomer(customerId: UUID)
}

/** Port penerbit access-token portal (JWT HS256, secret terpisah dari operator). */
interface PortalAccessTokenIssuer {

    fun issue(customerId: UUID, tenantId: UUID, login: String, name: String): PortalIssuedToken
}

data class PortalIssuedToken(
    val value: String,
    val expiresAt: Instant,
)

/** Port hashing password portal — implementasi (BCrypt bersama) berada di adapter. */
interface PortalPasswordHasher {

    fun hash(rawPassword: String): String

    fun matches(rawPassword: String, passwordHash: String): Boolean
}
