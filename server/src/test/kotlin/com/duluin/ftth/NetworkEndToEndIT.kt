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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji end-to-end Phase 1: rantai inventory OLT→ODC→ODP, aturan penempatan ONU
 * pada port ODP, komposisi lintas-module di endpoint GIS, dan isolasi tenant
 * untuk aset jaringan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NetworkEndToEndIT {

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

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    /** Membangun rantai lengkap POP → OLT → PON → ODC → ODP dan mengembalikan id ODP. */
    private fun buildChain(token: String, capacity: Int = 8): String {
        val suffix = uniq().uppercase()
        val site = idOf(
            post(
                "/api/sites", token,
                """{"code":"POP-$suffix","name":"POP $suffix","location":{"longitude":106.98,"latitude":-6.23}}""",
            ),
        )
        val olt = idOf(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$suffix","name":"OLT $suffix","vendor":"ZTE",
                    "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}""",
            ),
        )
        val pon = idOf(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$suffix","name":"ODC $suffix","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        return idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":$capacity}""",
            ),
        )
    }

    private fun attachNewCustomer(token: String, odpId: String, port: Int, expected: Int = 200): String {
        val suffix = uniq().uppercase()
        val customer = idOf(
            post(
                "/api/customers", token,
                """{"code":"CUST-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji No. 1",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val onu = idOf(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$suffix"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", expected)
        return customer
    }

    @Test
    fun `satu port ODP hanya boleh ditempati satu ONU`() {
        val token = newTenantAdmin("port")
        val odp = buildChain(token)

        attachNewCustomer(token, odp, port = 3)
        // Port yang sama untuk pelanggan lain harus ditolak sebagai konflik.
        attachNewCustomer(token, odp, port = 3, expected = 409)
    }

    @Test
    fun `nomor port di luar kapasitas ODP ditolak`() {
        val token = newTenantAdmin("cap")
        val odp = buildChain(token, capacity = 8)

        attachNewCustomer(token, odp, port = 99, expected = 400)
    }

    @Test
    fun `ODP yang masih dipakai pelanggan tidak bisa dihapus`() {
        val token = newTenantAdmin("del")
        val odp = buildChain(token)
        attachNewCustomer(token, odp, port = 1)

        // Menjaga agar ONU tidak menggantung: FK-nya ON DELETE SET NULL, sehingga
        // tanpa penjagaan ini penghapusan berhasil diam-diam.
        mockMvc.perform(delete("/api/odps/$odp").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `panel ODP menampilkan hulu lengkap dan pelanggan yang menempel`() {
        val token = newTenantAdmin("panel")
        val odp = buildChain(token, capacity = 8)
        attachNewCustomer(token, odp, port = 2)
        attachNewCustomer(token, odp, port = 5)

        val json = mockMvc.perform(get("/api/gis/odps/$odp").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<Int>(json, "$.usedPorts")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(json, "$.capacity")).isEqualTo(8)
        assertThat(JsonPath.read<Int>(json, "$.utilizationPercent")).isEqualTo(25)
        assertThat(JsonPath.read<List<Int>>(json, "$.availablePortNumbers")).containsExactly(1, 3, 4, 6, 7, 8)
        // Rantai hulu terisi penuh sampai site — prasyarat monitoring di Phase 2.
        assertThat(JsonPath.read<Boolean>(json, "$.upstream.complete")).isTrue()
        assertThat(JsonPath.read<List<Int>>(json, "$.occupants[*].portNumber")).containsExactly(2, 5)
    }

    @Test
    fun `telusur jalur pelanggan sampai OLT beserta anggaran redaman`() {
        val token = newTenantAdmin("trace")
        val odp = buildChain(token)
        val customer = attachNewCustomer(token, odp, port = 1)

        val json = mockMvc.perform(
            get("/api/gis/trace/customers/$customer").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(json, "$.hops[*].kind"))
            .containsExactly("CUSTOMER", "ODP", "ODC", "PON_PORT", "OLT", "SITE")
        // Dua tingkat splitter 1:8 => 2 x 10,5 dB, ditambah redaman serat.
        assertThat(JsonPath.read<Double>(json, "$.upstream.splitterLossDb")).isEqualTo(21.0)
        assertThat(JsonPath.read<Double>(json, "$.estimatedLossDb")).isGreaterThan(21.0)
    }

    @Test
    fun `community string SNMP tidak pernah dikembalikan API`() {
        val token = newTenantAdmin("snmp")
        buildChain(token)

        val json = mockMvc.perform(get("/api/olts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(json).doesNotContain("rahasia")
        assertThat(JsonPath.read<Boolean>(json, "$.content[0].snmpConfigured")).isTrue()
    }

    @Test
    fun `aset jaringan tenant lain tidak terlihat`() {
        val tokenA = newTenantAdmin("neta")
        val tokenB = newTenantAdmin("netb")
        buildChain(tokenA)

        val json = mockMvc.perform(get("/api/odps").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<Int>(json, "$.totalElements")).isZero()
    }

    @Test
    fun `kabel drop tidak boleh menghubungkan pasangan simpul yang mustahil`() {
        val token = newTenantAdmin("cable")
        val suffix = uniq().uppercase()
        val site = idOf(
            post(
                "/api/sites", token,
                """{"code":"POP-$suffix","name":"POP $suffix","location":{"longitude":106.98,"latitude":-6.23}}""",
            ),
        )
        val olt = idOf(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$suffix","name":"OLT $suffix","vendor":"ZTE"}""",
            ),
        )
        val odp = buildChain(token)

        // DROP hanya sah dari ODP ke rumah pelanggan, bukan dari OLT.
        post(
            "/api/cables", token,
            """{"code":"CBL-$suffix","name":"Kabel $suffix","cableType":"DROP","coreCount":1,
                "route":[{"longitude":106.98,"latitude":-6.23},{"longitude":106.99,"latitude":-6.24}],
                "fromKind":"OLT","fromId":"$olt","toKind":"ODP","toId":"$odp"}""",
            expected = 400,
        )
    }

    @Test
    fun `vector tile berisi layer jaringan dan pelanggan`() {
        val token = newTenantAdmin("tile")
        val odp = buildChain(token)
        attachNewCustomer(token, odp, port = 1)

        // Tile z14 yang meliputi titik uji di sekitar Bekasi.
        val bytes = mockMvc.perform(get("/api/gis/tiles/14/13061/8476.mvt").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray

        assertThat(bytes).isNotEmpty()
        // Nama layer tersimpan apa adanya di protobuf MVT, jadi cukup dicari
        // sebagai byte — menghindari menyeret dependensi parser hanya untuk uji ini.
        val text = String(bytes, Charsets.ISO_8859_1)
        assertThat(text).contains("odp")
        assertThat(text).contains("customer")
    }
}
