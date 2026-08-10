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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Fisik jalur kabel: cara pasang (udara/tanam/duct) & kepemilikan.
 *
 * Yang dijaga di sini terutama SATU hal: cara pasang yang belum diketahui tetap
 * `null`, tak pernah ditebak jadi AERIAL. Data karangan di kolom ini bukan cuma
 * kotor — ia mengirim tim bertangga ke gangguan yang sebenarnya ada di dalam
 * duct, dan sebaliknya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CablePhysicalAttributesIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val body = """{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin)
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    /** Ujung-ujung kabel distribusi yang lazim: satu ODC ke satu ODP. */
    private fun newEnds(token: String): Pair<String, String> {
        val suffix = uniq().uppercase()
        val odc = idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$suffix","name":"ODC $suffix","location":{"longitude":106.99,"latitude":-6.24},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val odp = idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.995,"latitude":-6.245},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
        return odc to odp
    }

    /** Badan kabel lengkap; [extra] menyisipkan bidang fisik yang sedang diuji. */
    private fun cableBody(odc: String, odp: String, extra: String = ""): String =
        """{"name":"Distribusi uji","cableType":"DISTRIBUTION","coreCount":8,
            "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
            "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"$extra}"""

    @Test
    fun `kabel tanpa keterangan fisik lahir belum disurvei dan milik sendiri`() {
        val token = newTenantAdmin("fisik")
        val (odc, odp) = newEnds(token)

        val created = post("/api/cables", token, cableBody(odc, odp))

        // Tak diisi = tak ditebak. Ini yang membedakannya dari kolom ber-default.
        assertThat(JsonPath.read<Any?>(created, "$.installation")).isNull()
        assertThat(JsonPath.read<Any?>(created, "$.installationLabel")).isNull()
        // Kepemilikan berbeda: "milik sendiri" adalah kasus normal, bukan tebakan.
        assertThat(JsonPath.read<String>(created, "$.ownership")).isEqualTo("OWNED")
        assertThat(JsonPath.read<String>(created, "$.ownershipLabel")).isEqualTo("Milik sendiri")
    }

    @Test
    fun `hasil survei tersimpan lengkap dengan labelnya dan boleh dicabut kembali`() {
        val token = newTenantAdmin("survei")
        val (odc, odp) = newEnds(token)
        val cable = idOf(post("/api/cables", token, cableBody(odc, odp)))

        // Surveyor turun: ternyata jalur duct, dan sheath-nya sewa.
        val surveyed = put(
            "/api/cables/$cable", token,
            cableBody(odc, odp, ""","installation":"DUCT","ownership":"LEASED""""),
        )
        assertThat(JsonPath.read<String>(surveyed, "$.installation")).isEqualTo("DUCT")
        assertThat(JsonPath.read<String>(surveyed, "$.installationLabel")).isEqualTo("Duct / HDPE")
        assertThat(JsonPath.read<String>(surveyed, "$.ownershipLabel")).isEqualTo("Sewa")

        // Tersimpan sungguhan, bukan cuma memantul di respons.
        assertThat(JsonPath.read<String>(getJson("/api/cables/$cable", token), "$.installation")).isEqualTo("DUCT")

        // Salah catat boleh dikembalikan ke "belum disurvei" — nilai lama tak lengket.
        val cleared = put("/api/cables/$cable", token, cableBody(odc, odp, ""","installation":null"""))
        assertThat(JsonPath.read<Any?>(cleared, "$.installation")).isNull()
        assertThat(JsonPath.read<String>(cleared, "$.ownership")).isEqualTo("OWNED")
    }

    @Test
    fun `cara pasang di luar daftar ditolak`() {
        val token = newTenantAdmin("fisiksalah")
        val (odc, odp) = newEnds(token)

        post("/api/cables", token, cableBody(odc, odp, ""","installation":"TIANG"""), expected = 400)
    }
}
