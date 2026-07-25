package com.duluin.ftth

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji plotting OTDR: jarak-ke-gangguan yang dicatat pada sebuah kabel harus jadi
 * titik perkiraan di jalurnya, dengan arah ukur (dari hulu vs hilir) dan jarak
 * melampaui panjang kabel ditangani benar.
 *
 * Kabel lurus ODC→ODP dipakai agar titik tengah bisa diprediksi: jarak = separuh
 * panjang optik harus jatuh tepat di tengah geometri.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtdrTestIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun num(json: String, path: String): Double = (JsonPath.read<Any>(json, path) as Number).toDouble()

    @Test
    fun `jarak OTDR jadi titik perkiraan di jalur kabel, hormati arah ukur dan panjang`() {
        val slug = "otdr${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Otdr Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

        // Rantai POP → OLT → PON → ODC → ODP untuk menautkan kabel distribusi.
        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""),
        )
        val olt = id(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$s","name":"OLT $s","vendor":"ZTE",
                    "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}""",
            ),
        )
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$s","name":"ODC $s","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        val odp = id(
            post(
                "/api/odps", token,
                """{"code":"ODP-$s","name":"ODP $s","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
            ),
        )
        // Kabel lurus ODC (106.99,-6.24) → ODP (106.995,-6.245). Titik tengah = (106.9925,-6.2425).
        val cable = id(
            post(
                "/api/cables", token,
                """{"code":"DST-$s","name":"Distribusi $s","cableType":"DISTRIBUTION","coreCount":12,
                    "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
            ),
        )
        val length = num(getJson("/api/cables/$cable", token), "$.lengthMeters")

        // Separuh panjang dari hulu → titik tengah geometri, belum melampaui kabel.
        val mid = post(
            "/api/cables/$cable/otdr", token,
            """{"distanceMeters":${length / 2}, "measuredFrom":"FROM", "eventType":"BREAK"}""",
        )
        assertThat(JsonPath.read<String>(mid, "$.eventType")).isEqualTo("BREAK")
        assertThat(JsonPath.read<Boolean>(mid, "$.beyondCable")).isFalse()
        assertThat(num(mid, "$.estimatedPoint.longitude")).isEqualTo(106.9925, within(1e-5))
        assertThat(num(mid, "$.estimatedPoint.latitude")).isEqualTo(-6.2425, within(1e-5))
        assertThat(JsonPath.read<String>(mid, "$.recordedByName")).isEqualTo("Admin")

        // Diukur dari hilir sejauh seperempat → 75% dari hulu (106.99375, -6.24375).
        val fromTo = post(
            "/api/cables/$cable/otdr", token,
            """{"distanceMeters":${length / 4}, "measuredFrom":"TO", "eventType":"HIGH_LOSS", "lossDb":3.2}""",
        )
        assertThat(num(fromTo, "$.estimatedPoint.longitude")).isEqualTo(106.99375, within(1e-5))
        assertThat(num(fromTo, "$.estimatedPoint.latitude")).isEqualTo(-6.24375, within(1e-5))

        // Jarak melampaui panjang kabel → titik dijepit ke ujung hilir, ditandai beyondCable.
        val beyond = post(
            "/api/cables/$cable/otdr", token,
            """{"distanceMeters":${length * 2}, "measuredFrom":"FROM", "eventType":"BREAK"}""",
        )
        assertThat(JsonPath.read<Boolean>(beyond, "$.beyondCable")).isTrue()
        assertThat(num(beyond, "$.estimatedPoint.longitude")).isEqualTo(106.995, within(1e-5))
        assertThat(num(beyond, "$.estimatedPoint.latitude")).isEqualTo(-6.245, within(1e-5))

        // Riwayat memuat ketiganya, terbaru dulu; hapus satu menyisakan dua.
        val listed = getJson("/api/cables/$cable/otdr", token)
        assertThat(JsonPath.read<List<String>>(listed, "$.[*].id")).hasSize(3)

        val firstId = JsonPath.read<String>(listed, "$.[0].id")
        mockMvc.perform(delete("/api/cables/$cable/otdr/$firstId").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
        assertThat(JsonPath.read<List<String>>(getJson("/api/cables/$cable/otdr", token), "$.[*].id")).hasSize(2)

        // Jarak negatif ditolak sebelum menyentuh domain.
        post(
            "/api/cables/$cable/otdr", token,
            """{"distanceMeters":-5, "measuredFrom":"FROM", "eventType":"BREAK"}""",
            expected = 400,
        )
    }
}
