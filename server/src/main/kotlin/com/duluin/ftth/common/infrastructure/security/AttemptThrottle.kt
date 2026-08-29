package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.TooManyRequestsException
import com.duluin.ftth.common.infrastructure.config.ThrottleProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Rem untuk endpoint publik yang menerima rahasia: masuk, pemulihan akun, daftar mandiri.
 *
 * Hitungannya jendela tetap dalam memori. Sengaja BUKAN di database: rem ini harus tetap
 * bekerja justru saat sedang dibanjiri, dan menulis satu baris per percobaan gagal ke
 * Postgres berarti penyerang mengendalikan beban tulis kita. Konsekuensinya jujur: hitungan
 * ini per-instance dan ikut hilang saat restart. Untuk topologi sekarang (satu container
 * `server` di belakang Caddy) itu tepat; kalau suatu saat di-scale mendatar, angkanya perlu
 * pindah ke penyimpanan bersama — kalau tidak, batas efektifnya terkali jumlah instance.
 *
 * Yang dihitung hanya percobaan **gagal**. Login berhasil menghapus jejak identitasnya,
 * jadi orang yang cuma salah ketik beberapa kali tak dihukum setelah akhirnya masuk. Ember
 * per-IP TIDAK ikut dibersihkan: penyerang yang menyemprot ratusan akun dari satu IP
 * biasanya sesekali berhasil, dan itu tak boleh jadi tombol reset baginya.
 *
 * Kunci ember diberi awalan lingkup (`op:`, `portal:`, …) supaya realm operator dan realm
 * pelanggan tak pernah berbagi hitungan — email operator dan email pelanggan bisa sama
 * persis, dan mengunci yang satu karena ulah yang lain akan sulit dilacak.
 */
@Component
class AttemptThrottle(private val props: ThrottleProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Jalankan percobaan masuk di bawah dua rem sekaligus (identitas & IP).
     *
     * [identity] sudah ternormalisasi oleh pemanggil (huruf kecil, nomor HP dalam bentuk
     * kanonis) — kalau tidak, "Budi@Mail.com" dan "budi@mail.com" jadi dua ember dan
     * batasnya bisa dilipatgandakan sekadar dengan mengubah kapitalisasi.
     *
     * Hanya [AuthenticationException] yang dihitung sebagai gagal. Error lain (validasi,
     * database mati) bukan tebakan password, dan menghukumnya akan mengubah gangguan
     * biasa menjadi penguncian massal.
     */
    fun <T> guardLogin(scope: String, ip: String?, identity: String, attempt: () -> T): T {
        if (!props.enabled) return attempt()
        val identityKey = "login:$scope:${identity.take(MAX_KEY_LENGTH)}"
        val ipKey = "login-ip:$scope:${ip.orEmpty()}"

        rejectIfExhausted(identityKey, props.loginPerIdentity, "Terlalu banyak percobaan masuk untuk akun ini")
        rejectIfExhausted(ipKey, props.loginPerIp, "Terlalu banyak percobaan masuk dari jaringan ini")

        try {
            val result = attempt()
            windows.remove(identityKey)
            return result
        } catch (ex: AuthenticationException) {
            hit(identityKey, props.loginPerIdentity)
            hit(ipKey, props.loginPerIp)
            throw ex
        }
    }

    /**
     * Ambil satu jatah dari ember bernama, atau tolak. Dipakai endpoint yang tak punya
     * konsep "gagal" untuk dihitung — permintaan kode pemulihan selalu tampak berhasil,
     * justru karena itu SETIAP panggilan harus dihitung.
     */
    fun spend(scope: String, ip: String?, quota: ThrottleProperties.Quota, message: String) {
        if (!props.enabled) return
        val key = "$scope:${ip.orEmpty()}"
        rejectIfExhausted(key, quota, message)
        hit(key, quota)
    }

    fun spendRecoveryRequest(ip: String?) = spend(
        "recovery-req", ip, props.recoveryPerIp,
        "Terlalu banyak permintaan kode pemulihan dari jaringan ini",
    )

    fun spendRecoveryRedeem(ip: String?) = spend(
        "recovery-use", ip, props.recoveryRedeemPerIp,
        "Terlalu banyak percobaan kode pemulihan dari jaringan ini",
    )

    fun spendSignup(ip: String?) = spend(
        "signup", ip, props.signupPerIp,
        "Terlalu banyak pendaftaran dari jaringan ini",
    )

    fun spendHotspotPortalContext(ip: String?) = spend(
        "hotspot-portal-context", ip, props.hotspotPortalContextPerIp,
        "Terlalu banyak permintaan portal dari jaringan ini",
    )

    /** Buang seluruh hitungan — hanya untuk uji. */
    internal fun clear() = windows.clear()

    private fun rejectIfExhausted(key: String, quota: ThrottleProperties.Quota, message: String) {
        val now = Instant.now()
        val window = windows[key] ?: return
        val endsAt = window.start.plus(quota.window)
        if (!now.isBefore(endsAt)) {
            windows.remove(key, window)
            return
        }
        if (window.hits >= quota.limit) {
            val retryAfter = Duration.between(now, endsAt)
            log.warn("Throttle menahan '{}' — {} percobaan, sisa tunggu {} detik", key, window.hits, retryAfter.seconds)
            throw TooManyRequestsException("$message. Coba lagi dalam ${humanize(retryAfter)}.", retryAfter)
        }
    }

    private fun hit(key: String, quota: ThrottleProperties.Quota) {
        val now = Instant.now()
        windows.compute(key) { _, current ->
            if (current == null || !now.isBefore(current.start.plus(quota.window))) Window(now, 1)
            else Window(current.start, current.hits + 1)
        }
        // Kunci yang jendelanya habis tak pernah dikunjungi lagi (IP berganti terus), jadi
        // tanpa sapuan sesekali peta ini tumbuh sepanjang umur proses. Ambangnya longgar:
        // menyapu tiap penulisan justru mengubah rem menjadi beban tersendiri.
        if (windows.size > SWEEP_THRESHOLD) sweep()
    }

    private fun sweep() {
        val longest = maxOf(
            props.loginPerIdentity.window, props.loginPerIp.window,
            props.recoveryPerIp.window, props.recoveryRedeemPerIp.window, props.signupPerIp.window,
        )
        val cutoff = Instant.now().minus(longest)
        windows.entries.removeIf { it.value.start.isBefore(cutoff) }
    }

    private fun humanize(duration: Duration): String = when {
        duration.toMinutes() >= 1 -> "${duration.toMinutes() + 1} menit"
        else -> "${maxOf(duration.seconds, 1)} detik"
    }

    private data class Window(val start: Instant, val hits: Int)

    private companion object {
        /** Identitas kepanjangan (mis. kiriman sampah) tak boleh jadi kunci raksasa. */
        const val MAX_KEY_LENGTH = 160
        const val SWEEP_THRESHOLD = 20_000
    }
}
