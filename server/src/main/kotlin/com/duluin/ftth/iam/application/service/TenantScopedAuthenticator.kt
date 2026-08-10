package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.TwoFactorRequiredException
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.iam.application.port.inbound.AuthTokens
import com.duluin.ftth.iam.application.port.outbound.AccessTokenIssuer
import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.RecoveryCodeRepository
import com.duluin.ftth.iam.application.port.outbound.RefreshTokenRepository
import com.duluin.ftth.iam.application.port.outbound.TotpEngine
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.RefreshToken
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.slf4j.LoggerFactory
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
@Suppress("LongParameterList")
class TenantScopedAuthenticator(
    private val userRepository: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val assembler: AuthViewAssembler,
    private val securityProperties: SecurityProperties,
    private val events: ApplicationEventPublisher,
    private val totp: TotpEngine,
    private val cipher: SecretCipher,
    private val recoveryCodes: RecoveryCodeRepository,
) {
    fun authenticateAndIssue(rawEmail: String, rawPassword: String, otpCode: String? = null): AuthTokens {
        val email = parseEmail(rawEmail) ?: throw invalidCredentials()
        val user = userRepository.findByEmail(email) ?: throw invalidCredentials()
        if (!user.active) throw AuthenticationException("Akun dinonaktifkan")
        if (!passwordHasher.matches(rawPassword, user.passwordHash)) throw invalidCredentials()
        if (user.twoFactorEnabled) verifySecondFactor(user, otpCode)
        return issue(user, "auth.login")
    }

    /**
     * Faktor kedua diperiksa SETELAH password, tak pernah sebaliknya: memintanya lebih dulu
     * akan memberi tahu penebak password bahwa email itu ada dan memakai 2FA — padahal
     * seluruh alur ini dirancang tak membocorkan email mana yang terdaftar.
     */
    private fun verifySecondFactor(user: User, otpCode: String?) {
        val presented = otpCode?.trim().orEmpty()
        if (presented.isEmpty()) {
            throw TwoFactorRequiredException("Masukkan kode dari aplikasi autentikator")
        }
        if (verifyTotp(user, presented) || consumeRecoveryCode(user, presented)) return
        // Kode salah dihitung sebagai percobaan gagal oleh rem di adapter — enam digit
        // hanya sejuta kemungkinan, dan tanpa rem itu bisa disapu habis dalam hitungan jam.
        throw AuthenticationException("Kode verifikasi salah")
    }

    private fun verifyTotp(user: User, presented: String): Boolean {
        val secret = user.totpSecret?.let(cipher::decrypt) ?: return false
        val step = totp.verify(secret, presented) ?: return false
        // Kode yang sudah terpakai ditolak walau jendelanya masih berjalan: yang sempat
        // melihat layar atau menyadap satu request tak boleh bisa memakainya lagi.
        if (!user.acceptTotpStep(step)) return false
        userRepository.save(user)
        return true
    }

    private fun consumeRecoveryCode(user: User, presented: String): Boolean {
        val normalized = presented.lowercase().replace(" ", "")
        val code = recoveryCodes.findByHash(user.id, Tokens.sha256(normalized))?.takeIf { !it.used }
            ?: return false
        code.markUsed()
        recoveryCodes.save(code)
        log.warn("Kode pemulihan 2FA dipakai untuk masuk: user={} tenant={}", user.id, user.tenantId)
        return true
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

    private companion object {
        val log = LoggerFactory.getLogger(TenantScopedAuthenticator::class.java)
    }
}
