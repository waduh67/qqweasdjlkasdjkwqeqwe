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
 * Kabel level-port: menarik kabel feeder/distribusi sekaligus menyetel uplink
 * logis (fisik = logis), okupansi port keluaran ditegakkan (satu port = satu
 * kabel), dan drop mencatat slot ODP sumbernya. Menghapus kabel melepas uplink.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CablePortEndpointsIT {

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

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    /** Simpul jaringan sebuah tenant, dengan id yang dibutuhkan uji port. */
    private data class Fixture(
        val olt: String,
        val pon: String,
        val pon2: String,
        val odc: String,
        val odp: String,
    )

    /**
     * Membangun POP → OLT → dua PON port, lalu ODC dan ODP yang SENGAJA belum
     * ber-uplink (tanpa `ponPortId`/`odcId`) supaya penarikan kabel yang
     * menyetelnya bisa diamati dari nol.
     */
    private fun bootstrap(token: String): Fixture {
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
        val pon = idOf(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val pon2 = idOf(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/2"}"""))
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
        return Fixture(olt = olt, pon = pon, pon2 = pon2, odc = odc, odp = odp)
    }

    private fun newOdc(token: String): String {
        val suffix = uniq().uppercase()
        return idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$suffix","name":"ODC $suffix","location":{"longitude":106.991,"latitude":-6.241},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
    }

    private fun newOdp(token: String): String {
        val suffix = uniq().uppercase()
        return idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.996,"latitude":-6.246},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
    }

    private fun feederBody(from: Fixture, ponPortId: String, toOdc: String): String {
        val suffix = uniq().uppercase()
        return """{"code":"FDR-$suffix","name":"Feeder $suffix","cableType":"FEEDER","coreCount":24,
            "route":[{"longitude":106.98,"latitude":-6.23},{"longitude":106.99,"latitude":-6.24}],
            "fromKind":"OLT","fromId":"${from.olt}","toKind":"ODC","toId":"$toOdc",
            "fromPonPortId":"$ponPortId"}"""
    }

    @Test
    fun `feeder menyetel PON port ODC dan menolak feeder kedua di PON yang sama`() {
        val token = newTenantAdmin("feeder")
        val fx = bootstrap(token)

        // ODC lahir tanpa uplink.
        val before = getJson("/api/odcs/${fx.odc}", token)
        assertThat(JsonPath.read<Any?>(before, "$.ponPortId")).isNull()
        assertThat(JsonPath.read<Boolean>(before, "$.energized")).isFalse()

        // Menarik feeder dari PON#1 menyetel uplink logis ODC dalam satu aksi.
        post("/api/cables", token, feederBody(fx, fx.pon, fx.odc))

        val after = getJson("/api/odcs/${fx.odc}", token)
        assertThat(JsonPath.read<String>(after, "$.ponPortId")).isEqualTo(fx.pon)
        assertThat(JsonPath.read<Boolean>(after, "$.energized")).isTrue()

        // PON#1 sudah dipakai: feeder kedua dari port yang sama ke ODC lain ditolak.
        post("/api/cables", token, feederBody(fx, fx.pon, newOdc(token)), expected = 409)

        // Tapi PON#2 di OLT yang sama masih bebas dipakai.
        post("/api/cables", token, feederBody(fx, fx.pon2, newOdc(token)))
    }

    @Test
    fun `distribusi menyetel ODC ODP dan menolak kaki splitter yang sudah dipakai`() {
        val token = newTenantAdmin("distrib")
        val fx = bootstrap(token)

        // ODP lahir tanpa uplink.
        assertThat(JsonPath.read<Any?>(getJson("/api/odps/${fx.odp}", token), "$.odcId")).isNull()

        // Kaki splitter 1 ODC → ODP menyetel odcId ODP.
        val suffix = uniq().uppercase()
        post(
            "/api/cables", token,
            """{"code":"DST-$suffix","name":"Distribusi $suffix","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                "fromKind":"ODC","fromId":"${fx.odc}","toKind":"ODP","toId":"${fx.odp}",
                "fromPortNumber":1}""",
        )

        assertThat(JsonPath.read<String>(getJson("/api/odps/${fx.odp}", token), "$.odcId")).isEqualTo(fx.odc)

        // Kaki 1 sudah dipakai: distribusi lain dari kaki yang sama ditolak.
        val other = newOdp(token)
        val suffix2 = uniq().uppercase()
        post(
            "/api/cables", token,
            """{"code":"DST-$suffix2","name":"Distribusi $suffix2","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.997,"latitude":-6.247}],
                "fromKind":"ODC","fromId":"${fx.odc}","toKind":"ODP","toId":"$other",
                "fromPortNumber":1}""",
            expected = 409,
        )

        // Kaki 2 masih bebas.
        val suffix3 = uniq().uppercase()
        post(
            "/api/cables", token,
            """{"code":"DST-$suffix3","name":"Distribusi $suffix3","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.997,"latitude":-6.247}],
                "fromKind":"ODC","fromId":"${fx.odc}","toKind":"ODP","toId":"$other",
                "fromPortNumber":2}""",
        )
    }

    @Test
    fun `kabel drop mencatat slot ODP sumber`() {
        val token = newTenantAdmin("drop")
        val fx = bootstrap(token)

        // CUSTOMER milik module lain — network tak memvalidasi keberadaannya, cukup
        // mencatat slot ODP asal drop untuk kejelasan "colok dari slot mana".
        val customer = UUID.randomUUID()
        val suffix = uniq().uppercase()
        val cable = post(
            "/api/cables", token,
            """{"code":"DRP-$suffix","name":"Drop $suffix","cableType":"DROP","coreCount":1,
                "route":[{"longitude":106.995,"latitude":-6.245},{"longitude":106.996,"latitude":-6.246}],
                "fromKind":"ODP","fromId":"${fx.odp}","toKind":"CUSTOMER","toId":"$customer",
                "fromPortNumber":5}""",
        )

        val id = idOf(cable)
        val fetched = getJson("/api/cables/$id", token)
        assertThat(JsonPath.read<Int>(fetched, "$.fromPortNumber")).isEqualTo(5)
        assertThat(JsonPath.read<String>(fetched, "$.fromKind")).isEqualTo("ODP")
        assertThat(JsonPath.read<String>(fetched, "$.toKind")).isEqualTo("CUSTOMER")

        // Slot 5 kini terpakai: drop lain dari slot yang sama ditolak.
        val suffix2 = uniq().uppercase()
        post(
            "/api/cables", token,
            """{"code":"DRP-$suffix2","name":"Drop $suffix2","cableType":"DROP","coreCount":1,
                "route":[{"longitude":106.995,"latitude":-6.245},{"longitude":106.996,"latitude":-6.246}],
                "fromKind":"ODP","fromId":"${fx.odp}","toKind":"CUSTOMER","toId":"${UUID.randomUUID()}",
                "fromPortNumber":5}""",
            expected = 409,
        )
    }

    @Test
    fun `menghapus feeder melepas PON port ODC`() {
        val token = newTenantAdmin("release")
        val fx = bootstrap(token)

        val cableId = idOf(post("/api/cables", token, feederBody(fx, fx.pon, fx.odc)))
        assertThat(JsonPath.read<String>(getJson("/api/odcs/${fx.odc}", token), "$.ponPortId")).isEqualTo(fx.pon)

        mockMvc.perform(delete("/api/cables/$cableId").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)

        val after = getJson("/api/odcs/${fx.odc}", token)
        assertThat(JsonPath.read<Any?>(after, "$.ponPortId")).isNull()
        assertThat(JsonPath.read<Boolean>(after, "$.energized")).isFalse()
    }

    @Test
    fun `source-ports OLT menandai PON port yang terpakai`() {
        val token = newTenantAdmin("srcports")
        val fx = bootstrap(token)
        post("/api/cables", token, feederBody(fx, fx.pon, fx.odc))

        val json = getJson("/api/cables/source-ports?kind=OLT&id=${fx.olt}", token)

        // Kedua PON port muncul; yang menganggap feeder tadi ditandai occupied.
        assertThat(JsonPath.read<List<String>>(json, "$[*].ponPortId"))
            .containsExactlyInAnyOrder(fx.pon, fx.pon2)
        assertThat(JsonPath.read<List<Boolean>>(json, "$[?(@.ponPortId=='${fx.pon}')].occupied"))
            .containsExactly(true)
        assertThat(JsonPath.read<List<Boolean>>(json, "$[?(@.ponPortId=='${fx.pon2}')].occupied"))
            .containsExactly(false)
    }
}
