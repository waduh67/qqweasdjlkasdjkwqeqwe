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
 * Uji pandangan per-OLT "ONU siapa saja di bawah OLT ini" lewat
 * `GET /api/gis/olts/{id}/onus` — pengganti daftar ONU global yang mencampur semua OLT.
 *
 * Diverifikasi di atas dua OLT bertetangga: tiap ONU hanya muncul di bawah OLT-nya
 * sendiri (lewat topologi OLT → PON → ODC → ODP → ONU), lengkap dengan pelanggan,
 * kode ODP, dan port-nya; terurut per kode ODP lalu nomor port.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GisOltOnusIT {

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

    private fun oltOnus(token: String, oltId: String): String =
        mockMvc.perform(get("/api/gis/olts/$oltId/onus").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    /** Membangun satu OLT dengan satu PON port dan satu ODC di bawahnya; kembalikan (oltId, odcId). */
    private fun oltWithOdc(token: String, site: String, tag: String): Pair<String, String> {
        val olt = id(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$tag","name":"OLT $tag","vendor":"ZTE",
                    "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}""",
            ),
        )
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$tag","name":"ODC $tag","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        return olt to odc
    }

    private fun odp(token: String, odc: String, code: String): String = id(
        post(
            "/api/odps", token,
            """{"code":"$code","name":"$code","location":{"longitude":106.995,"latitude":-6.245},
                "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
        ),
    )

    /** Daftarkan pelanggan lalu pasang ONU-nya di port ODP tertentu; kembalikan customerId. */
    private fun customerWithOnu(
        token: String,
        code: String,
        name: String,
        serial: String,
        odpId: String,
        port: Int,
    ): String {
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"$code","name":"$name","address":"Jl. Uji","location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", 200)
        return customer
    }

    @Test
    fun `daftar ONU per-OLT hanya memuat ONU di bawah OLT itu, dengan pelanggan dan ODP-nya`() {
        val slug = "olto${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Olt Onu Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""),
        )

        // OLT-A dengan dua ODP (kode -1 lebih dulu dari -2 saat diurut).
        val (oltA, odcA) = oltWithOdc(token, site, "A$s")
        val odpA1 = odp(token, odcA, "ODP-$s-1")
        val odpA2 = odp(token, odcA, "ODP-$s-2")
        // OLT-B tetangga dengan satu ODP — ONU-nya tak boleh bocor ke daftar OLT-A.
        val (oltB, odcB) = oltWithOdc(token, site, "B$s")
        val odpB1 = odp(token, odcB, "ODP-$s-B1")

        // Andi di ODP-A1 port 2; Budi di ODP-A2 port 1; Citra di OLT-B.
        val andi = customerWithOnu(token, "C-$s-A1", "Andi $s", "SN-$s-A1", odpA1, 2)
        val budi = customerWithOnu(token, "C-$s-A2", "Budi $s", "SN-$s-A2", odpA2, 1)
        val citra = customerWithOnu(token, "C-$s-B1", "Citra $s", "SN-$s-B1", odpB1, 1)

        val a = oltOnus(token, oltA)
        assertThat(JsonPath.read<Int>(a, "$.onuCount")).isEqualTo(2)
        // Terurut per kode ODP (…-1 sebelum …-2) lalu port: Andi dulu, baru Budi.
        assertThat(JsonPath.read<List<String>>(a, "$.onus[*].serialNumber")).containsExactly("SN-$s-A1", "SN-$s-A2")
        assertThat(JsonPath.read<List<String>>(a, "$.onus[*].customerName")).containsExactly("Andi $s", "Budi $s")
        assertThat(JsonPath.read<List<String>>(a, "$.onus[*].odpCode")).containsExactly("ODP-$s-1", "ODP-$s-2")
        assertThat(JsonPath.read<List<Int>>(a, "$.onus[*].portNumber")).containsExactly(2, 1)
        assertThat(JsonPath.read<List<String>>(a, "$.onus[*].customerId")).containsExactly(andi, budi)
        // Citra (OLT-B) tak ikut.
        assertThat(JsonPath.read<List<String>>(a, "$.onus[*].customerId")).doesNotContain(citra)

        // OLT-B: hanya Citra.
        val b = oltOnus(token, oltB)
        assertThat(JsonPath.read<Int>(b, "$.onuCount")).isEqualTo(1)
        assertThat(JsonPath.read<List<String>>(b, "$.onus[*].serialNumber")).containsExactly("SN-$s-B1")
        assertThat(JsonPath.read<List<String>>(b, "$.onus[*].customerId")).containsExactly(citra)
    }

    @Test
    fun `OLT tanpa ONU terpasang mengembalikan daftar kosong`() {
        val slug = "olte${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Empty Olt Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""),
        )
        val (olt, odc) = oltWithOdc(token, site, "E$s")
        odp(token, odc, "ODP-$s-1") // ODP ada, tapi belum ada ONU terpasang.

        val json = oltOnus(token, olt)
        assertThat(JsonPath.read<Int>(json, "$.onuCount")).isEqualTo(0)
        assertThat(JsonPath.read<List<String>>(json, "$.onus")).isEmpty()
    }
}
