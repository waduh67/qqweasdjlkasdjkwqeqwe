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
 * Uji simulasi "kalau kabel ini putus, siapa yang kena" lewat
 * `GET /api/gis/cables/{id}/blast-radius`.
 *
 * Sebuah putus memutus segala yang dialiri lewat ruas itu, jadi dampaknya
 * bergantung pada ujung hilir kabel: putus feeder membunuh ODC beserta seluruh
 * subpohonnya, putus distribusi membunuh ODP sasaran, putus drop membunuh satu
 * pelanggan. Ketiganya diverifikasi di atas satu rantai POP→OLT→ODC→ODP dengan
 * seorang pelanggan terpasang.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GisCutBlastRadiusIT {

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

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun cutBlast(token: String, cableId: String): String =
        mockMvc.perform(get("/api/gis/cables/$cableId/blast-radius").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `putus feeder, distribusi, dan drop masing-masing menjatuhkan cakupan yang benar`() {
        val slug = "cut${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Cut Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

        // Rantai POP → OLT → PON → ODC → ODP
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

        // Seorang pelanggan dengan ONU terpasang di port 1 ODP.
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji","location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$s"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odp","portNumber":1}""", 200)

        // Feeder POP→ODC, distribusi ODC→ODP, drop ODP→pelanggan.
        val feeder = id(
            post(
                "/api/cables", token,
                """{"code":"FDR-$s","name":"Feeder $s","cableType":"FEEDER","coreCount":24,
                    "route":[{"longitude":106.98,"latitude":-6.23},{"longitude":106.99,"latitude":-6.24}],
                    "fromKind":"SITE","fromId":"$site","toKind":"ODC","toId":"$odc"}""",
            ),
        )
        val distribution = id(
            post(
                "/api/cables", token,
                """{"code":"DST-$s","name":"Distribusi $s","cableType":"DISTRIBUTION","coreCount":12,
                    "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
            ),
        )
        val drop = id(
            post(
                "/api/cables", token,
                """{"code":"DRP-$s","name":"Drop $s","cableType":"DROP","coreCount":2,
                    "route":[{"longitude":106.995,"latitude":-6.245},{"longitude":106.996,"latitude":-6.246}],
                    "fromKind":"ODP","fromId":"$odp","toKind":"CUSTOMER","toId":"$customer"}""",
            ),
        )

        // Putus feeder → ODC di ujung, seluruh subpohon lenyap: 1 ODP, 1 pelanggan,
        // dan ketiga kabel (feeder, distribusi, drop) tersorot.
        val feederCut = cutBlast(token, feeder)
        assertThat(JsonPath.read<String>(feederCut, "$.severedRootKind")).isEqualTo("ODC")
        assertThat(JsonPath.read<Int>(feederCut, "$.odcCount")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(feederCut, "$.odpCount")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(feederCut, "$.customerCount")).isEqualTo(1)
        assertThat(JsonPath.read<List<String>>(feederCut, "$.customers[*].customerId")).containsExactly(customer)
        assertThat(JsonPath.read<List<String>>(feederCut, "$.severedCables[*].id"))
            .contains(feeder, distribution, drop)

        // Putus distribusi → ODP di ujung: pelanggan itu kena, ODC tak dihitung.
        val distributionCut = cutBlast(token, distribution)
        assertThat(JsonPath.read<String>(distributionCut, "$.severedRootKind")).isEqualTo("ODP")
        assertThat(JsonPath.read<Int>(distributionCut, "$.odcCount")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(distributionCut, "$.odpCount")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(distributionCut, "$.customerCount")).isEqualTo(1)
        assertThat(JsonPath.read<List<String>>(distributionCut, "$.severedCables[*].id"))
            .contains(distribution, drop).doesNotContain(feeder)

        // Putus drop → satu pelanggan di ujung, tanpa ODP/ODC.
        val dropCut = cutBlast(token, drop)
        assertThat(JsonPath.read<String>(dropCut, "$.severedRootKind")).isEqualTo("CUSTOMER")
        assertThat(JsonPath.read<Int>(dropCut, "$.odpCount")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(dropCut, "$.customerCount")).isEqualTo(1)
        assertThat(JsonPath.read<List<String>>(dropCut, "$.customers[*].customerId")).containsExactly(customer)
    }
}
