package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.portal.application.port.inbound.PortalAuthTokens
import com.duluin.ftth.portal.application.port.inbound.PortalAuthenticationUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalLoginCommand
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantStatus
import org.springframework.stereotype.Service

/**
 * Orkestrasi autentikasi portal. SENGAJA tidak `@Transactional`: me-resolve tenant dari
 * slug lebih dulu, memasang [TenantContext], baru memanggil worker transaksional
 * [PortalTenantScopedAuthenticator] agar session Hibernate terbuka dengan tenant benar.
 *
 * Login memakai SLUG tenant eksplisit (bukan email global seperti operator) karena login
 * pelanggan boleh kembar antar-tenant — slug yang membedakan realm-nya.
 */
@Service
class PortalAuthenticationService(
    private val tenantApi: TenantApi,
    private val authenticator: PortalTenantScopedAuthenticator,
    private val refreshTokens: PortalRefreshTokenRepository,
) : PortalAuthenticationUseCase {

    override fun login(command: PortalLoginCommand): PortalAuthTokens {
        val slug = command.tenantSlug.trim().lowercase()
        // Slug tak dikenal → pesan sama dengan password salah (jangan bocorkan tenant mana ada).
        val tenant = tenantApi.findBySlug(slug) ?: throw AuthenticationException("Login atau password salah")
        if (tenant.status != TenantStatus.ACTIVE) throw AuthenticationException("Layanan tenant sedang tidak aktif")

        return TenantContext.runAs(tenant.id) {
            authenticator.authenticateAndIssue(command.login, command.password)
        }
    }

    override fun refresh(refreshToken: String): PortalAuthTokens {
        val presented = refreshTokens.findByTokenHash(PortalTokens.sha256(refreshToken))
            ?.takeIf { it.isActive() }
            ?: throw AuthenticationException("Refresh token tidak valid atau kadaluarsa")

        return TenantContext.runAs(presented.tenantId) {
            authenticator.rotateAndIssue(presented)
        }
    }

    override fun logout(refreshToken: String) {
        val presented = refreshTokens.findByTokenHash(PortalTokens.sha256(refreshToken)) ?: return
        TenantContext.runAs(presented.tenantId) {
            authenticator.revoke(presented)
        }
    }
}
