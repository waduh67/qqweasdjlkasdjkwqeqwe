package com.duluin.ftth

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
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
import java.util.UUID

/**
 * Cek kapasitas untuk survey: "alamat ini bisa dipasang atau tidak".
 *
 * Yang diuji bukan sekadar "ODP terdekat ketemu", melainkan urutan kesimpulan
 * yang dipakai orang di lapangan: kotak yang benar-benar siap pakai lebih dulu;
 * kalau semua penuh, selubung yang lewat di depan gang beserta core menganggurnya
 * — sebab di situlah beda antara "tidak bisa" dan "bisa minggu depan".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SurveyCapacityIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val lat = -6.24

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun survey(token: String, lon: Double, radius: Int = 300): String =
        mockMvc.perform(
            get("/api/network/survey/capacity?longitude=$lon&latitude=$lat&radiusMeters=$radius")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun newOdp(token: String, lon: Double, capacity: Int, ratio: String = "1:8"): String = id(
        post(
            "/api/odps", token,
            """{"code":"ODP-${uniq().uppercase()}","name":"ODP survey",
                "location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"$ratio","capacity":$capacity}""",
        ),
    )

    /** Mengisi satu port ODP dengan pelanggan sungguhan — okupansi dibaca dari ONU, bukan dikarang. */
    private fun isiPort(token: String, odpId: String, port: Int) {
        val kode = uniq().uppercase()
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$kode","name":"Pelanggan $kode","address":"Jl. Survey",
                    "location":{"longitude":107.0,"latitude":$lat}}""",
            ),
        )
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$kode"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", 200)
    }

    /**
     * Dua kotak dalam jangkauan: yang paling dekat sudah penuh, yang agak jauh
     * masih lapang. Jawaban yang benar adalah yang lapang — "terdekat" saja bukan
     * jawaban kalau pelanggannya tetap tak bisa dipasang di situ.
     */
    @Test
    fun `kotak yang masih lapang diangkat ke depan meski bukan yang paling dekat`() {
        val token = newTenantAdmin("survey")
        val penuh = newOdp(token, 107.0002, capacity = 2)
        val lapang = newOdp(token, 107.0015, capacity = 8)
        repeat(2) { isiPort(token, penuh, it + 1) }
        isiPort(token, lapang, 1)

        val hasil = survey(token, 107.0)

        assertThat(JsonPath.read<Boolean>(hasil, "$.serviceable")).isTrue()
        assertThat(JsonPath.read<String>(hasil, "$.verdict")).contains("Bisa dipasang")
        val kotak = JsonPath.read<List<Map<String, Any>>>(hasil, "$.odps")
        assertThat(kotak).hasSize(2)
        assertThat(kotak[0]["odpId"]).isEqualTo(lapang)
        assertThat(kotak[0]["freePorts"]).isEqualTo(7)
        assertThat(kotak[0]["ready"]).isEqualTo(true)
        // Yang penuh tetap dilaporkan — surveyor perlu tahu ia sudah dicek, bukan terlewat.
        assertThat(kotak[1]["odpId"]).isEqualTo(penuh)
        assertThat(kotak[1]["ready"]).isEqualTo(false)
        assertThat(kotak[1]["note"] as String).contains("penuh")
        // Jarak dihitung dari titik survey, dan yang lapang memang lebih jauh.
        assertThat(kotak[0]["distanceMeters"] as Double).isGreaterThan(kotak[1]["distanceMeters"] as Double)
    }

    /**
     * Semua kotak penuh, tapi ada selubung lewat di depan gang dengan core
     * menganggur. Sistem yang cuma menghitung port kosong akan menjawab "tidak
     * bisa" — padahal cukup dikupas di tengah bentang, dan itulah yang dijawab.
     */
    @Test
    fun `kotak penuh bukan jawaban akhir selama ada core menganggur di kabel yang lewat`() {
        val token = newTenantAdmin("kupas")
        val penuh = newOdp(token, 107.0002, capacity = 1)
        isiPort(token, penuh, 1)
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-${uniq().uppercase()}","name":"ODC survey",
                    "location":{"longitude":106.99,"latitude":$lat},"splitterRatio":"1:8","capacity":8}""",
            ),
        )
        // Selubung 8 core membentang lewat depan titik survey, menuju kotak yang penuh itu.
        val selubung = id(
            post(
                "/api/cables", token,
                """{"code":"DST-${uniq().uppercase()}","name":"Distribusi survey",
                    "cableType":"DISTRIBUTION","coreCount":8,
                    "route":[{"longitude":106.99,"latitude":$lat},{"longitude":107.01,"latitude":$lat}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$penuh"}""",
            ),
        )

        val hasil = survey(token, 107.0)

        assertThat(JsonPath.read<Boolean>(hasil, "$.serviceable")).isFalse()
        assertThat(JsonPath.read<String>(hasil, "$.verdict")).contains("core menganggur")
        val kabel = JsonPath.read<List<Map<String, Any>>>(hasil, "$.cables")
        assertThat(kabel).hasSize(1)
        assertThat(kabel[0]["cableId"]).isEqualTo(selubung)
        assertThat(kabel[0]["freeCores"]).isEqualTo(8)
        assertThat(kabel[0]["freeCoreNumbers"] as List<*>).startsWith(1, 2, 3)
        // Titik survey di tengah bentang: kupasannya kira-kira separuh panjang kabel,
        // dan jarak tegak lurus ke jalurnya nyaris nol karena ia persis di atas garis.
        assertThat(kabel[0]["distanceMeters"] as Double).isLessThan(20.0)
        assertThat(kabel[0]["tapDistanceMeters"] as Double).isBetween(900.0, 1_500.0)
    }

    /**
     * Di luar jangkauan, jawabannya harus kalimat yang bisa diucapkan — bukan
     * daftar kosong yang menyuruh orang menyimpulkan sendiri.
     */
    @Test
    fun `titik di luar jangkauan dijawab dengan kalimat, bukan daftar kosong`() {
        val token = newTenantAdmin("jauh")
        newOdp(token, 107.05, capacity = 8)

        val hasil = survey(token, 107.0)

        assertThat(JsonPath.read<Boolean>(hasil, "$.serviceable")).isFalse()
        assertThat(JsonPath.read<List<*>>(hasil, "$.odps")).isEmpty()
        assertThat(JsonPath.read<List<*>>(hasil, "$.cables")).isEmpty()
        assertThat(JsonPath.read<String>(hasil, "$.verdict")).contains("di luar jangkauan")
    }
}
