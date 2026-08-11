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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Splitter sebagai benda tersendiri, bukan lagi satu kolom di kabinet.
 *
 * Yang berubah bukan sekadar tempat menyimpan angkanya. Kabinet sungguhan berisi
 * BEBERAPA modul dengan rasio berbeda — 1:8 untuk perumahan padat, 1:16 untuk
 * gang sebelah — dan ada pula ODC yang sama sekali tak bersplitter karena
 * tugasnya cuma menyilangkan feeder. Keduanya mustahil dinyatakan dengan satu
 * kolom `splitter_ratio`, dan keduanya lumrah di lapangan.
 *
 * Isian "rasio splitter" di form ODC/ODP tetap ada sebagai jalan pintas untuk
 * bentuk yang paling umum (satu modul), tapi ia cuma pintu masuk — aturannya
 * satu, di layar splitter maupun di form kabinet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SplitterIT {

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

    private fun putJson(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun deleteAt(url: String, token: String, expected: Int = 204): String =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    /** `ratio` null = kabinet tanpa splitter, dan itu memang bentuk ODC cross-connect. */
    private fun newOdc(token: String, lon: Double = 106.99, lat: Double = -6.24, ratio: String? = "1:8"): String {
        val shortcut = ratio?.let { ""","splitterRatio":"$it"""" } ?: ""
        return idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji",
                    "location":{"longitude":$lon,"latitude":$lat}$shortcut,"capacity":8}""",
            ),
        )
    }

    private fun newOdp(token: String, lon: Double = 107.005, lat: Double = -6.24, ratio: String? = "1:8"): String {
        val shortcut = ratio?.let { ""","splitterRatio":"$it"""" } ?: ""
        return idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-${uniq().uppercase()}","name":"ODP uji",
                    "location":{"longitude":$lon,"latitude":$lat}$shortcut,"capacity":8}""",
            ),
        )
    }

    private fun contentsOf(token: String, ownerKind: String, ownerId: String): String =
        getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token)

    private fun addSplitter(token: String, ownerKind: String, ownerId: String, ratio: String, expected: Int = 201) =
        post(
            "/api/splitters", token,
            """{"ownerKind":"$ownerKind","ownerId":"$ownerId","ratio":"$ratio"}""",
            expected,
        )

    /** Kabel distribusi 8 core dari [odc] ke [odp] lewat garis lurus di antaranya. */
    private fun newDistribution(token: String, odc: String, odp: String): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Distribusi uji","cableType":"DISTRIBUTION","coreCount":8,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":107.005,"latitude":-6.24}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
        ),
    )

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun connect(token: String, closureKind: String, closureId: String, a: String, b: String, expected: Int = 201) =
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"$closureKind","closureId":"$closureId","a":$a,"b":$b}""",
            expected,
        )

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""
    private fun splitterIn(id: String) = """{"kind":"SPLITTER_IN","nodeId":"$id"}"""
    private fun splitterOut(id: String, leg: Int) = """{"kind":"SPLITTER_OUT","nodeId":"$id","portNumber":$leg}"""

    @Test
    fun `kabinet lama tetap punya splitternya, lewat jalan pintas di form ODC`() {
        val token = newTenantAdmin("pintas")
        val odc = newOdc(token, ratio = "1:8")

        val isi = contentsOf(token, "ODC", odc)
        assertThat(JsonPath.read<List<*>>(isi, "$.splitters")).hasSize(1)
        assertThat(JsonPath.read<String>(isi, "$.splitters[0].ratio")).isEqualTo("1:8")
        assertThat(JsonPath.read<Int>(isi, "$.splitters[0].legCount")).isEqualTo(8)
        // Server yang menomori modulnya — orang di lapangan tak perlu mengarang label.
        assertThat(JsonPath.read<String>(isi, "$.splitters[0].code")).isEqualTo("SPL-1")

        // Jalan pintas yang sama juga MENGGANTI rasio modul tunggalnya.
        putJson(
            "/api/odcs/$odc", token,
            """{"code":"X","name":"ODC uji","location":{"longitude":106.99,"latitude":-6.24},
                "splitterRatio":"1:16","capacity":8}""",
        )
        assertThat(JsonPath.read<String>(contentsOf(token, "ODC", odc), "$.splitters[0].ratio")).isEqualTo("1:16")
    }

    @Test
    fun `ODC tanpa splitter itu sah - namanya cross-connect, bukan data yang kurang`() {
        val token = newTenantAdmin("silang")
        val odc = newOdc(token, ratio = null)

        assertThat(JsonPath.read<List<*>>(contentsOf(token, "ODC", odc), "$.splitters")).isEmpty()

        val view = getJson("/api/odcs/$odc", token)
        assertThat(JsonPath.read<String>(view, "$.splitterRatio")).isEqualTo("—")
        assertThat(JsonPath.read<Int>(view, "$.splitterCount")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(view, "$.splitterLegs")).isEqualTo(0)
    }

    /**
     * Bentuk yang selama ini mustahil: satu kabinet, beberapa modul. Ringkasan di
     * daftar ODC menjawab pertanyaan yang benar-benar ditanyakan orang penjualan —
     * "berapa yang masih bisa dijual dari kabinet ini" — bukan sekadar satu rasio.
     */
    @Test
    fun `satu kabinet boleh berisi beberapa modul dengan rasio berbeda`() {
        val token = newTenantAdmin("banyak")
        val odc = newOdc(token, ratio = "1:8")
        assertThat(JsonPath.read<String>(addSplitter(token, "ODC", odc, "1:16"), "$.code")).isEqualTo("SPL-2")
        addSplitter(token, "ODC", odc, "1:8")

        val view = getJson("/api/odcs/$odc", token)
        assertThat(JsonPath.read<String>(view, "$.splitterRatio")).isEqualTo("1:8 ×2 · 1:16")
        assertThat(JsonPath.read<Int>(view, "$.splitterCount")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(view, "$.splitterLegs")).isEqualTo(32)

        // Jalan pintas satu isian tak bisa mewakili tiga modul, jadi ia diam saja —
        // menebak mana yang dimaksud lebih berbahaya daripada tak berbuat apa-apa.
        putJson(
            "/api/odcs/$odc", token,
            """{"code":"X","name":"ODC uji","location":{"longitude":106.99,"latitude":-6.24},
                "splitterRatio":"1:4","capacity":8}""",
        )
        assertThat(JsonPath.read<Int>(getJson("/api/odcs/$odc", token), "$.splitterCount")).isEqualTo(3)
    }

    @Test
    fun `joint box tak bisa dititipi splitter`() {
        val token = newTenantAdmin("jbsplit")
        val jb = idOf(
            post(
                "/api/joint-boxes", token,
                """{"code":"JB-${uniq().uppercase()}","name":"JB uji",
                    "location":{"longitude":107.00,"latitude":-6.24},"trayCount":2,"capacity":24}""",
            ),
        )
        val error = addSplitter(token, "JOINT_BOX", jb, "1:8", expected = 400)
        assertThat(error).contains("tak berisi splitter")
    }

    /**
     * Menurunkan rasio berarti kaki 9-16 lenyap. Kalau salah satunya masih
     * menyuplai pelanggan, perubahan itu memutuskan orang di lapangan tanpa
     * seorang pun tahu — jadi ditolak, lengkap dengan nomor kaki yang menahannya.
     */
    @Test
    fun `rasio tak bisa diturunkan selama masih ada kaki terpakai di luar rasio baru`() {
        val token = newTenantAdmin("turun")
        val odc = newOdc(token, ratio = "1:16")
        val odp = newOdp(token)
        val cable = newDistribution(token, odc, odp)
        val spl = JsonPath.read<String>(contentsOf(token, "ODC", odc), "$.splitters[0].id")

        connect(token, "ODC", odc, core(coreId(token, cable, 1)), splitterOut(spl, 12))

        val error = putJson("/api/splitters/$spl", token, """{"ratio":"1:8"}""", expected = 409)
        assertThat(error).contains("kaki 12")

        // Naik selalu aman: kaki yang sudah terpakai tetap ada di rasio yang lebih besar.
        putJson("/api/splitters/$spl", token, """{"ratio":"1:32"}""")
        val isi = contentsOf(token, "ODC", odc)
        assertThat(JsonPath.read<Int>(isi, "$.splitters[0].legCount")).isEqualTo(32)
        assertThat(JsonPath.read<List<*>>(isi, "$.splitters[0].usedLegs")).containsExactly(12)
    }

    @Test
    fun `modul yang masih tersambung tak bisa dihapus, begitu juga kabinetnya`() {
        val token = newTenantAdmin("cabut")
        val odc = newOdc(token, ratio = "1:8")
        val odp = newOdp(token)
        val cable = newDistribution(token, odc, odp)
        val spl = JsonPath.read<String>(contentsOf(token, "ODC", odc), "$.splitters[0].id")

        connect(token, "ODC", odc, core(coreId(token, cable, 1)), splitterOut(spl, 1))

        assertThat(deleteAt("/api/splitters/$spl", token, expected = 409)).contains("kaki 1")
        // Kabinetnya pun ikut tertahan — ODC lenyap sementara seratnya masih
        // terpasang adalah kegagalan senyap yang baru ketahuan saat menelusuri gangguan.
        deleteAt("/api/odcs/$odc", token, expected = 409)
    }

    /**
     * Splitter bertingkat: kaki 1:4 di kabinet menyuplai modul 1:8 di kabinet yang
     * sama. Lumrah di ODC besar — feeder dipecah kasar dulu, baru dipecah halus
     * per arah — dan mustahil dicatat selama satu kabinet cuma boleh satu rasio.
     */
    @Test
    fun `splitter bertingkat di dalam satu kabinet itu sah`() {
        val token = newTenantAdmin("tingkat")
        val odc = newOdc(token, ratio = "1:4")
        val tingkat1 = JsonPath.read<String>(contentsOf(token, "ODC", odc), "$.splitters[0].id")
        val tingkat2 = idOf(addSplitter(token, "ODC", odc, "1:8"))

        connect(token, "ODC", odc, splitterOut(tingkat1, 1), splitterIn(tingkat2))

        val isi = contentsOf(token, "ODC", odc)
        assertThat(JsonPath.read<List<*>>(isi, "$.splitters[0].usedLegs")).containsExactly(1)
        assertThat(JsonPath.read<Boolean>(isi, "$.splitters[1].inputConnected")).isTrue()
    }
}
