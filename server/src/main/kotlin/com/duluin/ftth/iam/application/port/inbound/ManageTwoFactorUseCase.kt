package com.duluin.ftth.iam.application.port.inbound

import java.util.UUID

/**
 * Pengelolaan faktor kedua (TOTP) milik pengguna yang sedang masuk, plus satu jalur
 * administratif untuk mengosongkannya milik orang lain.
 *
 * Alurnya sengaja dua langkah — daftar lalu konfirmasi — supaya QR yang salah pindai
 * ketahuan SEBELUM 2FA mengunci akunnya. Antara keduanya, rahasia sudah tersimpan tapi
 * belum berlaku saat masuk.
 */
interface ManageTwoFactorUseCase {

    fun status(): TwoFactorStatusView

    /** Terbitkan rahasia baru + URI QR. Tak mengubah apa pun yang sedang berlaku. */
    fun startEnrollment(): TotpEnrollmentView

    /** Buktikan aplikasi autentikator sudah benar, aktifkan, lalu terbitkan kode pemulihan. */
    fun confirmEnrollment(code: String): RecoveryCodesView

    /** Matikan 2FA. Butuh password: sesi yang dicuri tak boleh cukup untuk melucuti pengaman. */
    fun disable(password: String)

    fun regenerateRecoveryCodes(password: String): RecoveryCodesView

    /** Kosongkan 2FA milik pengguna lain — untuk ponsel hilang tanpa kode pemulihan tersisa. */
    fun resetFor(userId: UUID)
}

data class TwoFactorStatusView(
    val enabled: Boolean,
    /** Ada rahasia yang sudah dipasang tapi belum dikonfirmasi. */
    val pending: Boolean,
    val recoveryCodesLeft: Int,
)

data class TotpEnrollmentView(
    /** Rahasia Base32 — ditampilkan untuk yang memasukkannya manual (tak bisa memindai QR). */
    val secret: String,
    val otpauthUri: String,
)

/** Kode pemulihan terbaca. HANYA muncul sekali, pada response yang menerbitkannya. */
data class RecoveryCodesView(
    val codes: List<String>,
)
