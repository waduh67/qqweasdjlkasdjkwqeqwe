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
 * Memisahkan rantai ODP → ODP yang sungguhan dari yang cuma penyamar satu
 * selubung menerus.
 *
 * Dua-duanya tergambar persis sama di peta: garis dari kotak ke kotak. Yang
 * membedakan ada di dalam kotaknya — apakah cahaya benar-benar dipecah dulu di
 * situ (splitter bertingkat), atau seratnya cuma lewat. Karena itu tiap uji di
 * sini membangun kotak yang sama lalu HANYA mengubah isi meja sambungnya, dan
 * mengharap jawaban yang berbeda.
 *
 * Yang sama pentingnya: uji terakhir memastikan sistem berani berkata "belum
 * bisa dipastikan". Menuduh operator berdasarkan kotak yang datanya memang belum
 * pernah diisi akan membuat peringatan ini diabaikan sejak hari pertama.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CableChainCheckIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val lat = -6.24

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val body = """{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
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

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun newOdc(token: String, lon: Double): String = idOf(
        post(
            "/api/odcs", token,
            """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji","location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newOdp(token: String, lon: Double): String = idOf(
        post(
            "/api/odps", token,
            """{"code":"ODP-${uniq().uppercase()}","name":"ODP uji","location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    @Suppress("LongParameterList")
    private fun newCable(
        token: String,
        cores: Int,
        fromKind: String,
        fromId: String,
        fromLon: Double,
        toKind: String,
        toId: String,
        toLon: Double,
    ): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Kabel uji","cableType":"DISTRIBUTION","coreCount":$cores,
                "route":[{"longitude":$fromLon,"latitude":$lat},{"longitude":$toLon,"latitude":$lat}],
                "fromKind":"$fromKind","fromId":"$fromId","toKind":"$toKind","toId":"$toId"}""",
        ),
    )

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""
    private fun splitterOut(id: String, leg: Int) = """{"kind":"SPLITTER_OUT","nodeId":"$id","portNumber":$leg}"""

    private fun connect(token: String, closureId: String, a: String, b: String) =
        post("/api/fiber-connections", token, """{"closureKind":"ODP","closureId":"$closureId","a":$a,"b":$b}""")

    private fun chainCheck(token: String, cable: String): String = getJson("/api/cables/$cable/chain-check", token)

    /** ODC di 107,00 → ODP-1 di 107,02 → ODP-2 di 107,04, dua ruas 8 core. */
    private data class Rantai(val odp1: String, val odp2: String, val masuk: String, val lanjut: String)

    private fun bangunRantai(token: String, coresLanjut: Int = 8): Rantai {
        val odc = newOdc(token, 107.00)
        val odp1 = newOdp(token, 107.02)
        val odp2 = newOdp(token, 107.04)
        val masuk = newCable(token, 8, "ODC", odc, 107.00, "ODP", odp1, 107.02)
        val lanjut = newCable(token, coresLanjut, "ODP", odp1, 107.02, "ODP", odp2, 107.04)
        return Rantai(odp1, odp2, masuk, lanjut)
    }

    /**
     * Rantai yang sah: kaki splitter kotak pertama menyuapi kotak berikutnya.
     *
     * Ini bukan kabel palsu melainkan splitter bertingkat — bentuk yang memang
     * dipasang orang saat satu kaki 1:8 dipecah lagi di kotak sebelah. Sistem
     * harus menyebut kaki yang mana, karena itulah yang dicari teknisi saat
     * membuka kotaknya.
     */
    @Test
    fun `kaki splitter yang menyuapi kotak berikutnya diakui sebagai rantai yang sah`() {
        val token = newTenantAdmin("cascade")
        val net = bangunRantai(token)
        connect(
            token, net.odp1,
            splitterOut(splitterOf(token, "ODP", net.odp1), 5),
            core(coreId(token, net.lanjut, 1)),
        )

        val hasil = chainCheck(token, net.lanjut)

        assertThat(JsonPath.read<String>(hasil, "$.verdict")).isEqualTo("CASCADE")
        assertThat(JsonPath.read<List<Int>>(hasil, "$.cascadeLegs")).containsExactly(5)
        assertThat(JsonPath.read<String>(hasil, "$.headline")).contains("sah")
        assertThat(JsonPath.read<String?>(hasil, "$.suggestion")).isNull()
    }

    /**
     * Sambungan LURUS core ke core: seratnya cuma lewat, kotaknya tempat
     * sambungan. Di lapangan ini bisa satu haspel yang dikupas, bisa juga dua
     * haspel yang bertemu — jadi jawabannya menuduh dengan sopan: sebut apa yang
     * terbaca, akui simulasi putusnya sudah benar, dan serahkan keputusannya.
     */
    @Test
    fun `core yang disambung lurus menandakan serat menerus, bukan splitter bertingkat`() {
        val token = newTenantAdmin("lurus")
        val net = bangunRantai(token)
        connect(token, net.odp1, core(coreId(token, net.masuk, 2)), core(coreId(token, net.lanjut, 2)))

        val hasil = chainCheck(token, net.lanjut)

        assertThat(JsonPath.read<String>(hasil, "$.verdict")).isEqualTo("SUSPECT")
        assertThat(JsonPath.read<String>(hasil, "$.headline")).contains("menerus")
        assertThat(JsonPath.read<List<String>>(hasil, "$.evidence").joinToString()).contains("LURUS")
        assertThat(JsonPath.read<String>(hasil, "$.suggestion")).contains("satu kabel menerus")
    }

    /**
     * Tanpa catatan sambungan apa pun, yang tersisa cuma bentuk datanya: kabel
     * masuk 8 core, kabel lanjut 8 core, kotak tanpa satu pun kaki terpakai.
     * Itu pola khas satu selubung yang dipecah — dan namanya disebut supaya bisa
     * diperiksa sendiri oleh yang membaca.
     */
    @Test
    fun `dua ruas berjumlah core sama tanpa kaki terpakai dicurigai satu selubung`() {
        val token = newTenantAdmin("pola")
        val net = bangunRantai(token)

        val hasil = chainCheck(token, net.lanjut)

        assertThat(JsonPath.read<String>(hasil, "$.verdict")).isEqualTo("SUSPECT")
        assertThat(JsonPath.read<String>(hasil, "$.upstreamCableId")).isEqualTo(net.masuk)
        assertThat(JsonPath.read<List<String>>(hasil, "$.evidence").joinToString())
            .contains("jumlah core yang sama")
            .contains("bentuk data")
    }

    /**
     * Jumlah core yang berbeda memutus satu-satunya petunjuk yang tersisa, dan
     * jawaban jujurnya adalah "belum bisa dipastikan" — lengkap dengan cara
     * membuatnya pasti. Kabel bukan-ODP→ODP bahkan tak perlu diperiksa.
     */
    @Test
    fun `tanpa petunjuk yang cukup sistem mengaku belum bisa memastikan`() {
        val token = newTenantAdmin("ragu")
        val net = bangunRantai(token, coresLanjut = 4)

        val ragu = chainCheck(token, net.lanjut)
        assertThat(JsonPath.read<String>(ragu, "$.verdict")).isEqualTo("UNKNOWN")
        assertThat(JsonPath.read<List<String>>(ragu, "$.evidence").joinToString()).contains("meja sambung")

        val bukanRantai = chainCheck(token, net.masuk)
        assertThat(JsonPath.read<String>(bukanRantai, "$.verdict")).isEqualTo("NOT_CHAINED")
        assertThat(JsonPath.read<List<*>>(bukanRantai, "$.evidence")).isEmpty()
    }
}
