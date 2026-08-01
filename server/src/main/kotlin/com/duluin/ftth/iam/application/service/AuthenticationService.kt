package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.AuthTokens
import com.duluin.ftth.iam.application.port.inbound.AuthenticationUseCase
import com.duluin.ftth.iam.application.port.inbound.LoginCommand
import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.application.port.outbound.UserDirectory
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantStatus
import org.springframework.stereotype.Service

/**
 * Orkestrasi autentikasi. SENGAJA tidak `@Transactional`: ia me-resolve tenant
 * lebih dulu, memasang [TenantContext], baru memanggil worker transaksional
 * [TenantScopedAuthenticator] agar session Hibernate terbuka dengan tenant yang benar.
 */
@Service
class AuthenticationService(
    private val tenantApi: TenantApi,
    private val authenticator: TenantScopedAuthenticator,
    private val refreshTokens: RefreshTokenRepository,
    private val userDirectory: UserDirectory,
) : AuthenticationUseCase {

    /**
     * Login TANPA slug tenant: email di-resolve ke tenant lewat [UserDirectory]
     * (indeks pre-auth, di luar RLS) — server belum tahu tenant sebelum email dikenali.
     * Pesan gagal disamakan (email tak dikenal vs password salah) agar tak membocorkan
     * email mana yang terdaftar.
     */
    override fun login(command: LoginCommand): AuthTokens {
        val tenantId = userDirectory.findTenantByEmail(command.email.trim().lowercase())
            ?: throw AuthenticationException("Email atau password salah")
        val tenant = tenantApi.findById(tenantId)
            ?: throw AuthenticationException("Email atau password salah")
        if (tenant.status != TenantStatus.ACTIVE) throw AuthenticationException("Tenant tidak aktif")

        return TenantContext.runAs(tenant.id) {
            authenticator.authenticateAndIssue(command.email, command.password)
        }
    }

    override fun refresh(refreshToken: String): AuthTokens {
        val presented = refreshTokens.findByTokenHash(Tokens.sha256(refreshToken))
            ?.takeIf { it.isActive() }
            ?: throw AuthenticationException("Refresh token tidak valid atau kadaluarsa")

        return TenantContext.runAs(presented.tenantId) {
            authenticator.rotateAndIssue(presented)
        }
    }

    override fun logout(refreshToken: String) {
        val presented = refreshTokens.findByTokenHash(Tokens.sha256(refreshToken)) ?: return
        TenantContext.runAs(presented.tenantId) {
            authenticator.revoke(presented)
        }
    }
}
