package com.duluin.ftth.common.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Batas laju untuk endpoint publik yang menerima rahasia (masuk, pemulihan akun, daftar
 * mandiri). Semuanya jendela tetap sederhana: `limit` kali dalam `window`.
 *
 * Dua dimensi dipakai bersamaan di layar masuk, karena keduanya menjaga hal berbeda:
 *  - **per-identitas** menahan penebakan password satu akun tertentu (target terarah);
 *  - **per-IP** menahan penyemprotan satu password ke banyak akun sekaligus (password
 *    spraying), yang tak pernah menyentuh batas per-identitas mana pun.
 *
 * Batas per-IP sengaja jauh lebih longgar: satu kantor ISP kerap keluar lewat satu NAT,
 * jadi angka yang ketat akan mengunci seluruh staf gara-gara satu orang salah ketik.
 */
@ConfigurationProperties(prefix = "ftth.throttle")
data class ThrottleProperties(
    /** Matikan hanya untuk uji beban/otomasi. Di produksi biarkan menyala. */
    val enabled: Boolean = true,
    /** Percobaan masuk GAGAL per identitas (email operator / identitas pelanggan). */
    val loginPerIdentity: Quota = Quota(8, Duration.ofMinutes(15)),
    /** Percobaan masuk GAGAL per alamat IP. */
    val loginPerIp: Quota = Quota(40, Duration.ofMinutes(15)),
    /** Permintaan kode pemulihan per IP — inilah yang menahan pemompaan WA/SMTP. */
    val recoveryPerIp: Quota = Quota(10, Duration.ofHours(1)),
    /** Penukaran kode pemulihan per IP — menahan penyemprotan kode 6 digit lintas akun. */
    val recoveryRedeemPerIp: Quota = Quota(30, Duration.ofHours(1)),
    /** Pendaftaran mandiri tenant baru per IP. */
    val signupPerIp: Quota = Quota(5, Duration.ofHours(6)),
) {
    data class Quota(val limit: Int, val window: Duration) {
        init {
            require(limit > 0) { "limit throttle harus > 0" }
            require(!window.isZero && !window.isNegative) { "window throttle harus > 0" }
        }
    }
}
