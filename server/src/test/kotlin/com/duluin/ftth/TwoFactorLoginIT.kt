package com.duluin.ftth

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.iam.adapter.outbound.security.Base32
import com.duluin.ftth.iam.adapter.outbound.security.Rfc6238TotpEngine
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Alur 2FA operator dari ujung ke ujung: pasang → masuk pakai kode → cadangan → lepas.
 *
 * Yang diuji di sini adalah hal-hal yang tak terlihat dari uji unit: bahwa rahasia
 * terenkripsi selamat pulang-pergi ke database, bahwa kode yang sudah terpakai ditolak
 * lintas-request (bukan cuma di dalam satu objek domain), dan bahwa kegagalan faktor kedua
 * betul-betul menahan penerbitan token — bukan sekadar mengubah pesan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TwoFactorLoginIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var throttle: AttemptThrottle

    /** Tipe konkret, bukan port: uji berperan sebagai aplikasi autentikator yang MEMBUAT kode. */
    @Autowired private lateinit var engine: Rfc6238TotpEngine

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    /**
     * Rem anti-tebak dihitung per-instance dan dipakai bersama seluruh suite dari IP yang
     * sama (127.0.0.1). Uji ini sengaja gagal masuk beberapa kali, jadi hitungannya
     * dibersihkan supaya tak ada uji lain yang terkena imbas urutan eksekusi.
     */
    @BeforeEach
    fun resetThrottle() = throttle.clear()

    private fun login(email: String, otpCode: String? = null, expected: Int = 200): String {
        val fields = buildList {
            add("\"email\":\"$email\"")
            add("\"password\":\"$pass\"")
            otpCode?.let { add("\"otpCode\":\"$it\"") }
        }
        return mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(fields.joinToString(",", "{", "}")),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString
    }

    private fun tokenOf(email: String, otpCode: String? = null): String =
        JsonPath.read(login(email, otpCode), "$.accessToken")

    private fun postAs(path: String, token: String, body: String = "{}", expected: Int = 200): String =
        mockMvc.perform(
            post(path).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect { assertThat(it.response.status).describedAs(path).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getJson(path: String, token: String): String =
        mockMvc.perform(get(path).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    /** Kode untuk langkah waktu tertentu, persis seperti yang akan ditampilkan ponsel. */
    private fun codeAt(secret: String, stepOffset: Long = 0): String =
        engine.codeFor(Base32.decode(secret), Instant.now().epochSecond / 30 + stepOffset)

    /** Onboard tenant, pasang 2FA sampai aktif; kembalikan (email, rahasia, kode pemulihan). */
    private fun enrolledAdmin(prefix: String): Triple<String, String, List<String>> {
        val slug = "$prefix${uniq()}"
        val email = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "TFA Co", email, "Admin", pass))
        val token = tokenOf(email)

        val secret: String = JsonPath.read(postAs("/api/me/2fa/setup", token), "$.secret")
        val codes: List<String> = JsonPath.read(
            postAs("/api/me/2fa/enable", token, """{"code":"${codeAt(secret)}"}"""),
            "$.codes",
        )
        return Triple(email, secret, codes)
    }

    @Test
    fun `pasang 2FA - status berubah dan kode pemulihan terbit sekali`() {
        val slug = "tfs${uniq()}"
        val email = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "TFA Co", email, "Admin", pass))
        val token = tokenOf(email)

        val before = getJson("/api/me/2fa", token)
        assertThat(JsonPath.read<Boolean>(before, "$.enabled")).isFalse()
        assertThat(JsonPath.read<Boolean>(before, "$.pending")).isFalse()

        val setup = postAs("/api/me/2fa/setup", token)
        val secret: String = JsonPath.read(setup, "$.secret")
        assertThat(Base32.decode(secret)).hasSize(20)
        assertThat(JsonPath.read<String>(setup, "$.otpauthUri")).startsWith("otpauth://totp/NetOps%20Console")

        // Rahasia sudah terpasang tapi belum dikonfirmasi → menunggu, belum aktif.
        val pending = getJson("/api/me/2fa", token)
        assertThat(JsonPath.read<Boolean>(pending, "$.enabled")).isFalse()
        assertThat(JsonPath.read<Boolean>(pending, "$.pending")).isTrue()

        // Kode ngawur tak boleh mengaktifkan apa pun.
        postAs("/api/me/2fa/enable", token, """{"code":"000000"}""", expected = 400)

        val codes: List<String> = JsonPath.read(
            postAs("/api/me/2fa/enable", token, """{"code":"${codeAt(secret)}"}"""),
            "$.codes",
        )
        assertThat(codes).hasSize(8).doesNotHaveDuplicates()
        assertThat(codes.first()).matches("[a-z2-9]{5}-[a-z2-9]{5}")

        val after = getJson("/api/me/2fa", token)
        assertThat(JsonPath.read<Boolean>(after, "$.enabled")).isTrue()
        assertThat(JsonPath.read<Int>(after, "$.recoveryCodesLeft")).isEqualTo(8)
        assertThat(JsonPath.read<Boolean>(getJson("/api/me", token), "$.twoFactorEnabled")).isTrue()
    }

    @Test
    fun `masuk tanpa kode ditolak dengan penanda khusus dan kode benar diterima sekali saja`() {
        val (email, secret, _) = enrolledAdmin("tfl")

        // Password benar tapi tanpa faktor kedua: 401 dengan penanda supaya UI tahu harus
        // menampilkan kolom kode, bukan bilang "password salah".
        val challenged = login(email, expected = 401)
        assertThat(JsonPath.read<String>(challenged, "$.code")).isEqualTo("TWO_FACTOR_REQUIRED")

        // Kode ngawur = kredensial salah biasa (dihitung rem), bukan tantangan ulang.
        val wrong = login(email, otpCode = "000000", expected = 401)
        assertThat(wrong).doesNotContain("TWO_FACTOR_REQUIRED")

        // Langkah berikutnya masih di dalam jendela toleransi, dan lebih baru daripada
        // langkah yang terpakai saat pendaftaran.
        val fresh = codeAt(secret, stepOffset = 1)
        assertThat(JsonPath.read<String>(login(email, fresh), "$.accessToken")).isNotBlank()

        // Kode yang sama disodorkan lagi dalam jendela 30 detik → ditolak.
        login(email, fresh, expected = 401)
    }

    @Test
    fun `kode pemulihan menggantikan aplikasi autentikator tepat satu kali`() {
        val (email, _, codes) = enrolledAdmin("tfr")

        val token = tokenOf(email, codes.first())
        assertThat(JsonPath.read<Int>(getJson("/api/me/2fa", token), "$.recoveryCodesLeft")).isEqualTo(7)

        // Kertas yang sudah dipakai tak berlaku lagi — termasuk saat diketik ulang dengan
        // kapitalisasi dan spasi seperti aslinya di layar.
        login(email, codes.first().uppercase(), expected = 401)

        // Yang lain masih berlaku.
        assertThat(JsonPath.read<String>(login(email, codes[1]), "$.accessToken")).isNotBlank()
    }

    @Test
    fun `mematikan 2FA butuh password dan mengembalikan login satu langkah`() {
        val (email, _, codes) = enrolledAdmin("tfd")
        val token = tokenOf(email, codes.first())

        postAs("/api/me/2fa/disable", token, """{"password":"salahsalah"}""", expected = 401)
        postAs("/api/me/2fa/disable", token, """{"password":"$pass"}""", expected = 204)

        assertThat(JsonPath.read<Boolean>(getJson("/api/me/2fa", token), "$.enabled")).isFalse()
        assertThat(JsonPath.read<String>(login(email), "$.accessToken")).isNotBlank()
        // Kode pemulihan lama ikut hangus bersama rahasianya.
        login(email, codes[1], expected = 200) // login biasa: kolom kode diabaikan saat 2FA mati
    }

    @Test
    fun `admin bisa mengosongkan 2FA milik pengguna yang kehilangan ponselnya`() {
        val (email, _, codes) = enrolledAdmin("tfx")
        val token = tokenOf(email, codes.first())
        val userId: String = JsonPath.read(getJson("/api/me", token), "$.id")

        postAs("/api/users/$userId/2fa/reset", token, expected = 204)

        assertThat(JsonPath.read<String>(login(email), "$.accessToken")).isNotBlank()
        assertThat(JsonPath.read<Boolean>(getJson("/api/me/2fa", token), "$.enabled")).isFalse()
    }
}
