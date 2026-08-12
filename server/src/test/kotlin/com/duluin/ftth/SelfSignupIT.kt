package com.duluin.ftth

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.tenancy.TenantApi
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID

/**
 * Pendaftaran mandiri ISP lewat HTTP, setelah kode ISP berhenti diketik pendaftar.
 *
 * Yang dibuktikan di sini adalah janji-janji yang tak bisa diuji unit test sendirian, karena
 * semuanya bergantung pada keadaan basis data yang sesungguhnya:
 *
 *  - kode ISP dirakit server dari nama, dan kode itu SUNGGUH bisa dipakai masuk — kalau tidak,
 *    pendaftar memegang kode yang tak membuka pintu apa pun;
 *  - dua ISP bernama sama tetap dapat kode berbeda (keunikannya dijamin unique index, bukan
 *    sekadar cek `findBySlug` yang bisa kalah balapan);
 *  - langganan tenant baru memakai harga default `/platform/billing`, bukan angka di kode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SelfSignupIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var throttle: AttemptThrottle
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var subscriptions: TenantSubscriptionRepository

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    /**
     * Endpoint publik ini direm 5 pendaftaran per IP per 6 jam, dan MockMvc memakai satu IP yang
     * sama untuk seluruh suite. Tanpa reset, tes yang dijalankan belakangan gagal karena rem —
     * bukan karena kodenya salah.
     */
    @BeforeEach
    fun resetThrottle() = throttle.clear()

    /** Kirim pendaftaran; sengaja TANPA `slug` — memang tak ada lagi kolomnya. */
    private fun signup(name: String, email: String, expected: Int = 201): String =
        mockMvc.perform(
            post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(
                """{"name":"$name","adminEmail":"$email","adminName":"Budi","adminPassword":"$pass"}""",
            ),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun login(slug: String, email: String, password: String = pass): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun platformToken(): String = login("platform", "root@ftth.local", "rootadmin123")

    @Test
    fun `kode ISP dirakit dari nama dan langsung bisa dipakai masuk`() {
        val id = uniq()
        val email = "budi-$id@netmedia.test"

        val response = signup("PT Net Media $id", email)

        val slug = JsonPath.read<String>(response, "$.slug")
        assertThat(slug).isEqualTo("pt-net-media-$id")
        // Kodenya wajib ikut terbaca di pesan: layar sukses menampilkannya apa adanya, dan itulah
        // satu-satunya tempat pendaftar melihatnya sebelum emailnya tiba.
        assertThat(JsonPath.read<String>(response, "$.message")).contains(slug)

        // Bukti bahwa kode yang dikembalikan memang kunci masuk yang sah, bukan sekadar teks.
        val token = login(slug, email)
        val me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<String>(me, "$.tenantSlug")).isEqualTo(slug)
        assertThat(JsonPath.read<String>(me, "$.email")).isEqualTo(email)
    }

    @Test
    fun `dua ISP bernama sama persis mendapat kode yang berbeda`() {
        val id = uniq()
        val name = "PT Sinar Jaya $id"

        val first = JsonPath.read<String>(signup(name, "satu-$id@sinarjaya.test"), "$.slug")
        val second = JsonPath.read<String>(signup(name, "dua-$id@sinarjaya.test"), "$.slug")

        assertThat(first).isEqualTo("pt-sinar-jaya-$id")
        // Bernomor, bukan diacak: kode yang kedua tetap harus terbaca manusia yang mengetiknya.
        assertThat(second).isEqualTo("pt-sinar-jaya-$id-2")
    }

    @Test
    fun `email yang sudah terdaftar ditolak 409, dan tak menyisakan tenant yatim`() {
        val id = uniq()
        val email = "kembar-$id@contoh.test"
        signup("PT Kembar $id", email)

        // Satu-satunya bentrok yang masih dilaporkan ke pendaftar — karena hanya ini yang bisa
        // ia perbaiki sendiri. Kode ISP tak lagi bisa bentrok di wajahnya.
        signup("PT Kembar Lain $id", email, expected = 409)

        assertThat(tenants.findBySlug("pt-kembar-lain-$id")).isNull()
    }

    @Test
    fun `langganan tenant baru memakai harga default platform`() {
        val token = platformToken()
        val before = mockMvc.perform(get("/api/platform/billing/settings").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val restore = JsonPath.read<Number>(before, "$.defaultMonthlyFee").toString()

        try {
            saveDefaultFee(token, "275000")
            val id = uniq()

            val slug = JsonPath.read<String>(signup("PT Harga Bawaan $id", "harga-$id@contoh.test"), "$.slug")

            val tenantId = tenants.findBySlug(slug)?.id
            assertThat(tenantId).isNotNull
            // Pendaftar publik tak pernah menyebut harga; yang berlaku adalah angka yang disetel
            // admin platform beberapa detik sebelumnya.
            assertThat(subscriptions.findByTenantId(tenantId!!)?.monthlyFee)
                .isEqualByComparingTo(BigDecimal("275000"))
        } finally {
            // Setelan ini global dan dibagi seluruh suite — mengembalikannya bukan kerapian,
            // melainkan syarat agar tes lain tak ikut terseret angka uji ini.
            saveDefaultFee(token, restore)
        }
    }

    private fun saveDefaultFee(token: String, fee: String) {
        mockMvc.perform(
            put("/api/platform/billing/settings").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"defaultGraceDays":7,"defaultDueDays":7,"defaultBillingDay":1,
                        "defaultMonthlyFee":$fee,"currency":"IDR"}""",
                ),
        ).andExpect(status().isOk)
    }
}
