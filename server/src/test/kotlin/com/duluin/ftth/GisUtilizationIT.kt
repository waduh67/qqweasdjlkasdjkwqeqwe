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
 * Uji heatmap utilisasi port ODP lewat `GET /api/gis/odp-utilization`.
 *
 * Angka utilisasi memadukan dua module tanpa satu pun menyentuh tabel milik yang
 * lain: kapasitas & lokasi ODP dari network, jumlah port terpakai dari customer.
 * Diverifikasi di atas dua ODP — satu terisi sebagian, satu kosong — supaya baik
 * persentase maupun kasus kapasitas-tanpa-pemakaian ikut teruji.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GisUtilizationIT {

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

    /** Mendaftarkan pelanggan lalu memasang ONU-nya di sebuah port ODP. */
    private fun occupyPort(token: String, odp: String, port: Int, tag: String) {
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$tag","name":"Pelanggan $tag","address":"Jl. Uji","location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$tag"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odp","portNumber":$port}""", 200)
    }

    @Test
    fun `heatmap melaporkan utilisasi port tiap ODP dalam jangkauan`() {
        val slug = "util${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Util Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

        // Rantai POP → OLT → PON → ODC untuk menaungi ODP.
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

        // ODP A kapasitas 8 dengan 2 port terpakai (25%); ODP B kapasitas 4 kosong (0%).
        val odpA = id(
            post(
                "/api/odps", token,
                """{"code":"ODP-A-$s","name":"ODP A $s","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val odpB = id(
            post(
                "/api/odps", token,
                """{"code":"ODP-B-$s","name":"ODP B $s","location":{"longitude":106.996,"latitude":-6.246},
                    "odcId":"$odc","splitterRatio":"1:4","capacity":4}""",
            ),
        )
        occupyPort(token, odpA, 1, "A1-$s")
        occupyPort(token, odpA, 2, "A2-$s")

        val json = mockMvc.perform(get("/api/gis/odp-utilization").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        // ODP A: 2 dari 8 port = 25%.
        assertThat(JsonPath.read<List<Int>>(json, "$.odps[?(@.odpId=='$odpA')].used")).containsExactly(2)
        assertThat(JsonPath.read<List<Int>>(json, "$.odps[?(@.odpId=='$odpA')].capacity")).containsExactly(8)
        assertThat(JsonPath.read<List<Int>>(json, "$.odps[?(@.odpId=='$odpA')].utilizationPercent")).containsExactly(25)

        // ODP B: kosong = 0%.
        assertThat(JsonPath.read<List<Int>>(json, "$.odps[?(@.odpId=='$odpB')].used")).containsExactly(0)
        assertThat(JsonPath.read<List<Int>>(json, "$.odps[?(@.odpId=='$odpB')].utilizationPercent")).containsExactly(0)
    }
}
