package com.duluin.ftth.common

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.TooManyRequestsException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.config.ThrottleProperties
import com.duluin.ftth.common.infrastructure.config.ThrottleProperties.Quota
import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Rem laju percobaan masuk.
 *
 * Yang paling dijaga di sini bukan "bisa menolak" — itu bagian gampangnya — melainkan
 * apa yang TIDAK boleh ikut kena rem: error selain gagal-autentikasi, akun lain yang
 * kebetulan seangkatan, dan realm yang berbeda.
 */
class AttemptThrottleTest {

    private fun throttle(
        perIdentity: Int = 3,
        perIp: Int = 100,
        enabled: Boolean = true,
        window: Duration = Duration.ofMinutes(15),
    ) = AttemptThrottle(
        ThrottleProperties(
            enabled = enabled,
            loginPerIdentity = Quota(perIdentity, window),
            loginPerIp = Quota(perIp, window),
        ),
    )

    private fun AttemptThrottle.failLogin(identity: String, ip: String? = "10.0.0.1", scope: String = "op") =
        runCatching { guardLogin(scope, ip, identity) { throw AuthenticationException("Email atau password salah") } }

    @Test
    fun `percobaan gagal beruntun akhirnya ditolak dengan 429 dan lama tunggu`() {
        val throttle = throttle(perIdentity = 3)
        repeat(3) { throttle.failLogin("budi@isp.com") }

        assertThatThrownBy { throttle.failLogin("budi@isp.com").getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)
            .hasMessageContaining("Terlalu banyak percobaan masuk")

        val ex = runCatching { throttle.failLogin("budi@isp.com").getOrThrow() }.exceptionOrNull()
        assertThat((ex as TooManyRequestsException).retryAfter).isPositive()
    }

    @Test
    fun `login berhasil menghapus jejak identitas itu`() {
        val throttle = throttle(perIdentity = 3)
        repeat(2) { throttle.failLogin("budi@isp.com") }

        throttle.guardLogin("op", "10.0.0.1", "budi@isp.com") { "token" }

        // Jatahnya utuh lagi: orang yang cuma salah ketik lalu berhasil tak boleh dihukum.
        repeat(3) { throttle.failLogin("budi@isp.com") }
        assertThatThrownBy { throttle.failLogin("budi@isp.com").getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)
    }

    @Test
    fun `akun lain dari IP sama tidak ikut terkunci`() {
        val throttle = throttle(perIdentity = 3, perIp = 100)
        repeat(4) { throttle.failLogin("budi@isp.com") }

        assertThatThrownBy { throttle.failLogin("budi@isp.com").getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)
        // Rekan sekantor di balik NAT yang sama harus tetap bisa mencoba.
        assertThat(throttle.failLogin("siti@isp.com").exceptionOrNull())
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `penyemprotan banyak akun dari satu IP tertahan rem per-IP`() {
        val throttle = throttle(perIdentity = 3, perIp = 5)
        // Tiap identitas beda, jadi rem per-identitas tak pernah tersentuh — hanya rem
        // per-IP yang bisa melihat polanya.
        repeat(5) { throttle.failLogin("korban$it@isp.com") }

        assertThatThrownBy { throttle.failLogin("korban99@isp.com").getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)
            .hasMessageContaining("dari jaringan ini")
    }

    @Test
    fun `realm operator dan portal punya hitungan sendiri`() {
        val throttle = throttle(perIdentity = 2)
        repeat(3) { throttle.failLogin("budi@mail.com", scope = "op") }

        assertThatThrownBy { throttle.failLogin("budi@mail.com", scope = "op").getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)
        // Email yang sama boleh saja dimiliki pelanggan; realmnya beda, embernya beda.
        assertThat(throttle.failLogin("budi@mail.com", scope = "portal").exceptionOrNull())
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `error selain gagal-autentikasi tidak dihitung`() {
        val throttle = throttle(perIdentity = 2)
        // Database mati / bug validasi bukan tebakan password. Menghitungnya akan mengubah
        // gangguan biasa menjadi penguncian massal justru saat keadaan sedang buruk.
        repeat(5) {
            runCatching { throttle.guardLogin("op", "10.0.0.1", "budi@isp.com") { throw ValidationException("rusak") } }
        }

        assertThat(throttle.failLogin("budi@isp.com").exceptionOrNull())
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `jendela yang sudah lewat memulihkan jatah`() {
        val throttle = throttle(perIdentity = 2, window = Duration.ofMillis(60))
        repeat(3) { throttle.failLogin("budi@isp.com") }
        assertThatThrownBy { throttle.failLogin("budi@isp.com").getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)

        Thread.sleep(90)

        assertThat(throttle.failLogin("budi@isp.com").exceptionOrNull())
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `kuota pemulihan menghitung setiap permintaan, bukan hanya yang gagal`() {
        val throttle = AttemptThrottle(ThrottleProperties(recoveryPerIp = Quota(2, Duration.ofHours(1))))

        throttle.spendRecoveryRequest("10.0.0.1")
        throttle.spendRecoveryRequest("10.0.0.1")

        assertThatThrownBy { throttle.spendRecoveryRequest("10.0.0.1") }
            .isInstanceOf(TooManyRequestsException::class.java)
            .hasMessageContaining("kode pemulihan")
        // IP lain tak ikut terbawa.
        assertThatCode { throttle.spendRecoveryRequest("10.0.0.2") }.doesNotThrowAnyException()
    }

    @Test
    fun `permintaan dan penukaran kode punya jatah terpisah`() {
        val throttle = AttemptThrottle(
            ThrottleProperties(
                recoveryPerIp = Quota(1, Duration.ofHours(1)),
                recoveryRedeemPerIp = Quota(3, Duration.ofHours(1)),
            ),
        )
        throttle.spendRecoveryRequest("10.0.0.1")

        // Jatah "minta kode" habis, tapi menukar kode yang SUDAH diterima harus tetap bisa —
        // kalau digabung, satu permintaan sah langsung mengunci orangnya dari menebus kodenya.
        assertThatCode { throttle.spendRecoveryRedeem("10.0.0.1") }.doesNotThrowAnyException()
    }

    @Test
    fun `rem yang dimatikan tak pernah menolak`() {
        val throttle = throttle(perIdentity = 1, enabled = false)
        repeat(20) { throttle.failLogin("budi@isp.com") }

        assertThat(throttle.failLogin("budi@isp.com").exceptionOrNull())
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `permintaan tanpa alamat IP tetap terhitung sebagai satu ember`() {
        val throttle = throttle(perIdentity = 100, perIp = 2)
        // `remoteAddr` bisa null di lingkungan tertentu; jangan sampai itu jadi jalan tikus
        // yang melewati rem sama sekali.
        repeat(2) { throttle.failLogin("a@isp.com", ip = null) }

        assertThatThrownBy { throttle.failLogin("b@isp.com", ip = null).getOrThrow() }
            .isInstanceOf(TooManyRequestsException::class.java)
    }
}
