package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.iam.domain.model.vo.Email
import java.time.Instant
import java.util.UUID

/**
 * Agregat User. Menyimpan hash password (hashing dilakukan port di luar domain),
 * kumpulan role, dan kumpulan area (scope data). Menegakkan invariant nama/status.
 */
@Suppress("TooManyFunctions")
class User private constructor(
    val id: UUID,
    val tenantId: UUID,
    email: Email,
    name: String,
    passwordHash: String,
    status: UserStatus,
    val platformAdmin: Boolean,
    roleIds: Set<UUID>,
    areaIds: Set<UUID>,
    val createdAt: Instant,
    totpSecret: String? = null,
    totpEnabledAt: Instant? = null,
    totpLastStep: Long? = null,
) {
    var email: Email = email
        private set

    var name: String = name
        private set

    var passwordHash: String = passwordHash
        private set

    var status: UserStatus = status
        private set

    var roleIds: Set<UUID> = roleIds
        private set

    var areaIds: Set<UUID> = areaIds
        private set

    /**
     * Rahasia TOTP dalam bentuk yang sudah dienkripsi lapisan luar. Domain sengaja
     * memperlakukannya sebagai teks buram: siapa yang mengenkripsi bukan urusan agregat.
     */
    var totpSecret: String? = totpSecret
        private set

    var totpEnabledAt: Instant? = totpEnabledAt
        private set

    var totpLastStep: Long? = totpLastStep
        private set

    val active: Boolean get() = status == UserStatus.ACTIVE

    /**
     * Rahasia yang sudah terpasang TAPI belum dikonfirmasi tak dihitung aktif — kalau
     * dihitung, salah pindai QR akan mengunci orangnya di luar akunnya sendiri.
     */
    val twoFactorEnabled: Boolean get() = totpEnabledAt != null

    fun rename(newName: String) {
        name = validateName(newName)
    }

    fun changePasswordHash(newHash: String) {
        require(newHash.isNotBlank()) { "Hash password kosong" }
        passwordHash = newHash
    }

    fun enable() {
        status = UserStatus.ACTIVE
    }

    fun disable() {
        status = UserStatus.DISABLED
    }

    fun assignRoles(roleIds: Set<UUID>) {
        this.roleIds = roleIds
    }

    fun assignAreas(areaIds: Set<UUID>) {
        this.areaIds = areaIds
    }

    /**
     * Pasang rahasia baru untuk didaftarkan. Menolak bila 2FA sudah aktif: mengganti
     * rahasia hanya dengan sesi yang sedang berjalan berarti penyerang yang berhasil
     * mencuri satu sesi bisa memindahkan faktor kedua ke ponselnya sendiri, diam-diam.
     * Matikan dulu (butuh password), baru daftar lagi.
     */
    fun beginTotpEnrollment(encryptedSecret: String) {
        require(encryptedSecret.isNotBlank()) { "Rahasia TOTP kosong" }
        if (twoFactorEnabled) throw ConflictException("2FA sudah aktif — matikan dulu sebelum mendaftar ulang")
        totpSecret = encryptedSecret
        totpLastStep = null
    }

    /** Menyelesaikan pendaftaran setelah satu kode terbukti benar. */
    fun confirmTotp(step: Long, now: Instant = Instant.now()) {
        if (totpSecret == null) throw ValidationException("Belum ada pendaftaran 2FA yang menunggu")
        totpEnabledAt = now
        totpLastStep = step
    }

    /**
     * Tandai satu langkah waktu TOTP terpakai. `false` = langkah itu (atau yang lebih tua)
     * sudah pernah dipakai — kode yang sama disodorkan dua kali, yang selama 30 detik
     * jendelanya adalah pemutaran ulang yang sah-sah saja bagi penyadap.
     */
    fun acceptTotpStep(step: Long): Boolean {
        val last = totpLastStep
        if (last != null && step <= last) return false
        totpLastStep = step
        return true
    }

    fun disableTotp() {
        totpSecret = null
        totpEnabledAt = null
        totpLastStep = null
    }

    companion object {
        fun create(
            tenantId: UUID,
            email: Email,
            name: String,
            passwordHash: String,
            platformAdmin: Boolean = false,
            roleIds: Set<UUID> = emptySet(),
            areaIds: Set<UUID> = emptySet(),
        ): User = User(
            id = UuidV7.generate(),
            tenantId = tenantId,
            email = email,
            name = validateName(name),
            passwordHash = passwordHash,
            status = UserStatus.ACTIVE,
            platformAdmin = platformAdmin,
            roleIds = roleIds,
            areaIds = areaIds,
            createdAt = Instant.now(),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            email: Email,
            name: String,
            passwordHash: String,
            status: UserStatus,
            platformAdmin: Boolean,
            roleIds: Set<UUID>,
            areaIds: Set<UUID>,
            createdAt: Instant,
            totpSecret: String? = null,
            totpEnabledAt: Instant? = null,
            totpLastStep: Long? = null,
        ): User = User(
            id, tenantId, email, name, passwordHash, status, platformAdmin, roleIds, areaIds, createdAt,
            totpSecret, totpEnabledAt, totpLastStep,
        )

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..255) throw ValidationException("Nama user harus 2-255 karakter")
            return trimmed
        }
    }
}
