package com.duluin.ftth.iam

import com.duluin.ftth.iam.adapter.outbound.security.Base32
import com.duluin.ftth.iam.adapter.outbound.security.Rfc6238TotpEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Verifikasi mesin TOTP terhadap **vektor uji resmi RFC 6238** (Appendix B, varian SHA-1).
 *
 * Ini uji yang paling berharga di seluruh slice 2FA: kalau implementasinya meleset satu
 * bit, semuanya tetap "jalan" — kode tetap keluar enam digit, verifikasi tetap konsisten
 * dengan dirinya sendiri — dan baru ketahuan salah saat operator memindai QR dengan Google
 * Authenticator sungguhan dan tak pernah bisa masuk. Vektor resmi adalah satu-satunya cara
 * membuktikan kita bicara protokol yang sama dengan aplikasi di ponsel orang.
 */
class Rfc6238TotpEngineTest {

    private val engine = Rfc6238TotpEngine()

    /** Rahasia vektor RFC 6238: ASCII "12345678901234567890" dalam Base32. */
    private val rfcSecret = Base32.encode("12345678901234567890".toByteArray())

    @Test
    fun `rahasia vektor RFC ter-encode Base32 seperti yang dipakai aplikasi autentikator`() {
        assertThat(rfcSecret).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertThat(Base32.decode(rfcSecret)).isEqualTo("12345678901234567890".toByteArray())
    }

    @Test
    fun `vektor uji resmi RFC 6238 menghasilkan kode yang sama`() {
        // (detik epoch, 6 digit terakhir dari kode 8 digit di Appendix B)
        val vectors = listOf(
            59L to "287082",
            1_111_111_109L to "081804",
            1_111_111_111L to "050471",
            1_234_567_890L to "005924",
            2_000_000_000L to "279037",
            20_000_000_000L to "353130",
        )
        vectors.forEach { (epochSecond, expected) ->
            val at = Instant.ofEpochSecond(epochSecond)
            assertThat(engine.verify(rfcSecret, expected, at))
                .describedAs("vektor t=$epochSecond kode=$expected")
                .isEqualTo(epochSecond / 30)
        }
    }

    @Test
    fun `jendela toleransi menerima satu langkah tetangga tapi tidak dua`() {
        val now = Instant.ofEpochSecond(1_700_000_000L)
        val step = now.epochSecond / 30
        val key = Base32.decode(rfcSecret)

        assertThat(engine.verify(rfcSecret, engine.codeFor(key, step - 1), now)).isEqualTo(step - 1)
        assertThat(engine.verify(rfcSecret, engine.codeFor(key, step + 1), now)).isEqualTo(step + 1)
        // Dua langkah = satu menit meleset. Di luar toleransi: jam sebegitu ngaco harus
        // diperbaiki, bukan diakomodasi dengan memperlebar jendela tebakan penyerang.
        assertThat(engine.verify(rfcSecret, engine.codeFor(key, step - 2), now)).isNull()
        assertThat(engine.verify(rfcSecret, engine.codeFor(key, step + 2), now)).isNull()
    }

    @Test
    fun `kode salah bentuk atau rahasia rusak ditolak tanpa melempar`() {
        val now = Instant.ofEpochSecond(1_700_000_000L)
        assertThat(engine.verify(rfcSecret, "", now)).isNull()
        assertThat(engine.verify(rfcSecret, "12345", now)).isNull()
        assertThat(engine.verify(rfcSecret, "1234567", now)).isNull()
        assertThat(engine.verify(rfcSecret, "abcdef", now)).isNull()
        // Rahasia bukan Base32 (mis. kolom terlanjur berisi sampah) tak boleh menjatuhkan
        // seluruh alur masuk — cukup jadi "kode salah".
        assertThat(engine.verify("bukan base32 !!!", "123456", now)).isNull()
    }

    @Test
    fun `spasi dan tanda hubung yang diketik manusia diabaikan`() {
        val now = Instant.ofEpochSecond(59L)
        assertThat(engine.verify(rfcSecret, "287 082", now)).isEqualTo(1L)
        assertThat(engine.verify(rfcSecret, "287-082", now)).isEqualTo(1L)
    }

    @Test
    fun `rahasia baru selalu 160 bit dan tak pernah berulang`() {
        val secrets = List(20) { engine.newSecret() }
        secrets.forEach { assertThat(Base32.decode(it)).hasSize(20) }
        assertThat(secrets.toSet()).hasSize(20)
        assertThat(secrets.first()).matches("[A-Z2-7]+")
    }

    @Test
    fun `URI provisioning memakai persen-encoding bukan aturan formulir`() {
        val uri = engine.provisioningUri(rfcSecret, "admin@contoh.test", "NetOps Console")

        assertThat(uri).startsWith("otpauth://totp/NetOps%20Console%3Aadmin%40contoh.test?")
        assertThat(uri).contains("secret=$rfcSecret")
        assertThat(uri).contains("issuer=NetOps%20Console")
        assertThat(uri).contains("algorithm=SHA1").contains("digits=6").contains("period=30")
        // Spasi sebagai '+' adalah aturan formulir HTML; aplikasi autentikator membacanya
        // sebagai plus harfiah dan menampilkan "NetOps+Console".
        assertThat(uri).doesNotContain("+")
    }
}
