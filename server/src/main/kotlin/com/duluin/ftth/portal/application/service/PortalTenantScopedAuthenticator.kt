package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalProfileView
import com.duluin.ftth.portal.application.port.outbound.PortalAccessTokenIssuer
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordHasher
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.domain.model.PortalRefreshToken
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Bagian autentikasi portal yang berjalan DI DALAM tenant context yang sudah dipasang
 * [PortalAuthenticationService] (via `TenantContext.runAs`). Batas `@Transactional` di
 * sini penting: session Hibernate baru terbuka setelah tenant ter-set, sehingga query
 * kredensial ter-scope tenant yang benar (RLS).
 *
 * Pesan error autentikasi seragam ("login atau password salah") untuk mencegah enumerasi.
 */
@Service
@Transactional
class PortalTenantScopedAuthenticator(
    private val credentials: PortalCredentialRepository,
    private val refreshTokens: PortalRefreshTokenRepository,
    private val passwordHasher: PortalPasswordHasher,
    private val accessTokenIssuer: PortalAccessTokenIssuer,
    private val securityProperties: SecurityProperties,
    private val customerApi: CustomerApi,
    private val tenantApi: TenantApi,
    private val events: ApplicationEventPublisher,
) {
    fun authenticateAndIssue(rawLogin: String, rawPassword: String): PortalAuthTokens {
        val login = normalizeLogin(rawLogin) ?: throw invalidCredentials()
        val credential = credentials.findByLogin(login) ?: throw invalidCredentials()
        if (!credential.active) throw AuthenticationException("Akun portal dinonaktifkan")
        if (!passwordHasher.matches(rawPassword, credential.passwordHash)) throw invalidCredentials()
        return issue(credential, "portal.auth.login")
    }

    fun rotateAndIssue(presented: PortalRefreshToken): PortalAuthTokens {
        presented.revoke()
        refreshTokens.save(presented)
        val credential = credentials.findByCustomerId(presented.customerId)?.takeIf { it.active }
            ?: throw AuthenticationException("Sesi portal tidak valid")
        return issue(credential, "portal.auth.refresh")
    }

    fun revoke(presented: PortalRefreshToken) {
        presented.revoke()
        refreshTokens.save(presented)
    }

    private fun issue(credential: PortalCredential, action: String): PortalAuthTokens {
        val tenantId = TenantContext.tenantId()
        val customer = customerApi.findCustomer(credential.customerId)
            ?: throw AuthenticationException("Pelanggan tidak ditemukan")
        val tenant = tenantApi.findById(tenantId)
            ?: throw AuthenticationException("Tenant tidak ditemukan")

        val access = accessTokenIssuer.issue(credential.customerId, tenantId, credential.login, customer.name)

        val rawRefresh = PortalTokens.random()
        val refreshExpiresAt = Instant.now().plus(securityProperties.refreshTokenTtl)
        refreshTokens.save(
            PortalRefreshToken.issue(tenantId, credential.customerId, PortalTokens.sha256(rawRefresh), refreshExpiresAt),
        )

        events.publishEvent(
            AuditTrailEvent(
                tenantId = tenantId,
                actorId = credential.customerId,
                actorEmail = credential.login,
                action = action,
                entityType = "PortalCredential",
                entityId = credential.customerId.toString(),
            ),
        )

        return PortalAuthTokens(
            accessToken = access.value,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = rawRefresh,
            refreshTokenExpiresAt = refreshExpiresAt,
            customer = PortalProfileView(
                customerId = credential.customerId,
                tenantId = tenantId,
                tenantSlug = tenant.slug,
                code = customer.code,
                name = customer.name,
                login = credential.login,
                phone = customer.phone,
                status = customer.status,
            ),
        )
    }

    private fun normalizeLogin(raw: String): String? =
        runCatching { PortalCredential.normalizeLogin(raw) }.getOrNull()

    private fun invalidCredentials() = AuthenticationException("Login atau password salah")
}
