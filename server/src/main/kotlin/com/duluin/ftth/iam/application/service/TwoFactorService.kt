package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.iam.application.port.inbound.ManageTwoFactorUseCase
import com.duluin.ftth.iam.application.port.inbound.RecoveryCodesView
import com.duluin.ftth.iam.application.port.inbound.TotpEnrollmentView
import com.duluin.ftth.iam.application.port.inbound.TwoFactorStatusView
import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.RecoveryCodeRepository
import com.duluin.ftth.iam.application.port.outbound.TotpEngine
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.RecoveryCode
import com.duluin.ftth.iam.domain.model.User
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.UUID

/**
 * Pemasangan & pelepasan faktor kedua.
 *
 * Rahasia TOTP dienkripsi DI SINI, bukan di adapter persistence: yang menyimpannya cuma
 * satu tempat, sedangkan yang membacanya ada dua (verifikasi saat masuk dan pembuatan
 * URI QR). Menempatkan enkripsi di jalur simpan-baca yang sama membuat keduanya mustahil
 * tak sinkron.
 */
@Service
@Transactional
class TwoFactorService(
    private val userRepository: UserRepository,
    private val recoveryCodes: RecoveryCodeRepository,
    private val totp: TotpEngine,
    private val cipher: SecretCipher,
    private val passwordHasher: PasswordHasher,
    private val currentUser: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
) : ManageTwoFactorUseCase {

    private val random = SecureRandom()

    @Transactional(readOnly = true)
    override fun status(): TwoFactorStatusView {
        val user = loadSelf()
        return TwoFactorStatusView(
            enabled = user.twoFactorEnabled,
            pending = !user.twoFactorEnabled && user.totpSecret != null,
            recoveryCodesLeft = if (user.twoFactorEnabled) recoveryCodes.countUnused(user.id) else 0,
        )
    }

    override fun startEnrollment(): TotpEnrollmentView {
        val user = loadSelf()
        val secret = totp.newSecret()
        user.beginTotpEnrollment(cipher.encrypt(secret))
        userRepository.save(user)
        return TotpEnrollmentView(
            secret = secret,
            otpauthUri = totp.provisioningUri(secret, user.email.value, BRAND),
        )
    }

    override fun confirmEnrollment(code: String): RecoveryCodesView {
        val user = loadSelf()
        val secret = user.totpSecret?.let(cipher::decrypt)
            ?: throw ValidationException("Belum ada pendaftaran 2FA yang menunggu — mulai dari awal")
        val step = totp.verify(secret, code)
            ?: throw ValidationException("Kode salah. Pastikan jam ponsel benar, lalu coba kode terbaru")

        user.confirmTotp(step)
        userRepository.save(user)
        audit("user.2fa_enabled", user)
        return issueRecoveryCodes(user)
    }

    override fun disable(password: String) {
        val user = loadSelf()
        requirePassword(user, password)
        user.disableTotp()
        userRepository.save(user)
        recoveryCodes.deleteAllForUser(user.id)
        audit("user.2fa_disabled", user)
    }

    override fun regenerateRecoveryCodes(password: String): RecoveryCodesView {
        val user = loadSelf()
        requirePassword(user, password)
        if (!user.twoFactorEnabled) throw ValidationException("2FA belum aktif")
        audit("user.2fa_recovery_regenerated", user)
        return issueRecoveryCodes(user)
    }

    /**
     * Jalur admin untuk ponsel yang hilang. Bukan "menyetel ulang 2FA orang lain menjadi
     * miliknya": yang terjadi hanyalah 2FA-nya dikosongkan, dan pemiliknya harus
     * mendaftarkan perangkat baru sendiri. Tercatat di audit karena inilah yang akan
     * dicari lebih dulu kalau suatu saat ada akun yang disalahgunakan.
     */
    override fun resetFor(userId: UUID) {
        val user = userRepository.findById(userId)
            ?: throw NotFoundException("User $userId tidak ditemukan")
        user.disableTotp()
        userRepository.save(user)
        recoveryCodes.deleteAllForUser(user.id)
        audit("user.2fa_reset", user)
    }

    private fun issueRecoveryCodes(user: User): RecoveryCodesView {
        val plain = List(RECOVERY_CODE_COUNT) { newRecoveryCode() }
        recoveryCodes.replaceAll(
            user.id,
            plain.map { RecoveryCode.issue(user.tenantId, user.id, Tokens.sha256(it)) },
        )
        return RecoveryCodesView(plain)
    }

    /**
     * Format `xxxx-xxxx-xx`: dibaca dari kertas dan diketik ulang manusia, jadi abjadnya
     * membuang karakter yang gampang tertukar (0/O, 1/l/I) dan dipotong dengan tanda
     * hubung. Entropinya ~50 bit — jauh di atas apa pun yang bisa ditebak.
     */
    private fun newRecoveryCode(): String {
        val raw = (1..RECOVERY_CODE_CHARS).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        return raw.chunked(GROUP_SIZE).joinToString("-")
    }

    private fun loadSelf(): User = userRepository.findById(currentUser.current().userId)
        ?: throw NotFoundException("Pengguna tidak ditemukan")

    private fun requirePassword(user: User, password: String) {
        if (!passwordHasher.matches(password, user.passwordHash)) {
            throw AuthenticationException("Password salah")
        }
    }

    private fun audit(action: String, user: User) {
        val actor = currentUser.currentOrNull()
        events.publishEvent(
            AuditTrailEvent(
                tenantId = user.tenantId,
                actorId = actor?.userId,
                actorEmail = actor?.email,
                action = action,
                entityType = "User",
                entityId = user.id.toString(),
                detail = mapOf("email" to user.email.value),
            ),
        )
    }

    private companion object {
        const val BRAND = "NetOps Console"
        const val RECOVERY_CODE_COUNT = 8
        const val RECOVERY_CODE_CHARS = 10
        const val GROUP_SIZE = 5
        const val CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
