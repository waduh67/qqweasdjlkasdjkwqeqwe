package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.iam.application.port.inbound.AuthTokens
import com.duluin.ftth.iam.application.port.outbound.AccessTokenIssuer
import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.RefreshToken
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Bagian autentikasi yang berjalan DI DALAM tenant context yang sudah dipasang
 * pemanggil ([AuthenticationService] via `TenantContext.runAs`). Batas
 * `@Transactional` di sini penting: session Hibernate baru terbuka setelah tenant
 * ter-set, sehingga query user/role ter-scope ke tenant yang benar.
 *
 * Pesan error autentikasi sengaja seragam ("kredensial tidak valid") untuk
 * mencegah kebocoran informasi (user enumeration).
 */
@Service
@Transactional
class TenantScopedAuthenticator(
    private val userRepository: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val assembler: AuthViewAssembler,
    private val securityProperties: SecurityProperties,
    private val events: ApplicationEventPublisher,
) {
    fun authenticateAndIssue(rawEmail: String, rawPassword: String): AuthTokens {
        val email = parseEmail(rawEmail) ?: throw invalidCredentials()
        val user = userRepository.findByEmail(email) ?: throw invalidCredentials()
        if (!user.active) throw AuthenticationException("Akun dinonaktifkan")
        if (!passwordHasher.matches(rawPassword, user.passwordHash)) throw invalidCredentials()
        return issue(user, "auth.login")
    }

    fun rotateAndIssue(presented: RefreshToken): AuthTokens {
        presented.revoke()
        refreshTokens.save(presented)
        val user = userRepository.findById(presented.userId)?.takeIf { it.active }
            ?: throw AuthenticationException("Sesi tidak valid")
        return issue(user, "auth.refresh")
    }

    fun revoke(presented: RefreshToken) {
        presented.revoke()
        refreshTokens.save(presented)
    }

    private fun issue(user: User, action: String): AuthTokens {
        val permissionCodes = assembler.permissionCodesFor(user)
        val access = accessTokenIssuer.issue(user, permissionCodes)

        val rawRefresh = Tokens.random()
        val refreshExpiresAt = Instant.now().plus(securityProperties.refreshTokenTtl)
        refreshTokens.save(
            RefreshToken.issue(user.tenantId, user.id, Tokens.sha256(rawRefresh), refreshExpiresAt),
        )

        events.publishEvent(
            AuditTrailEvent(
                tenantId = user.tenantId,
                actorId = user.id,
                actorEmail = user.email.value,
                action = action,
                entityType = "User",
                entityId = user.id.toString(),
            ),
        )

        return AuthTokens(
            accessToken = access.value,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = rawRefresh,
            refreshTokenExpiresAt = refreshExpiresAt,
            user = assembler.toAuthUserView(user, permissionCodes),
        )
    }

    private fun parseEmail(raw: String): Email? = runCatching { Email.of(raw) }.getOrNull()

    private fun invalidCredentials() = AuthenticationException("Email atau password salah")
}
