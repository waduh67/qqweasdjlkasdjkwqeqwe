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
 * Joint box — kotak sambung yang selama ini hilang dari model.
 *
 * Skenario induknya adalah sambungan haspel: kabel dijual per haspel, jadi jalur
 * panjang SELALU terpotong jadi beberapa kabel yang bertemu di kotak sambung.
 * Dulu itu memaksa operator menggambar satu kabel utuh yang di lapangan sudah
 * lama tak utuh — dan redaman tiap sambungan tak pernah tercatat di mana pun.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JointBoxIT {

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

    private fun deleteAt(url: String, token: String, expected: Int = 204): String =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun newOdc(token: String, lon: Double, lat: Double): String = idOf(
        post(
            "/api/odcs", token,
            """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji","location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newOdp(token: String, lon: Double, lat: Double): String = idOf(
        post(
            "/api/odps", token,
            """{"code":"ODP-${uniq().uppercase()}","name":"ODP uji","location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newJointBox(
        token: String,
        lon: Double,
        lat: Double,
        capacity: Int = 24,
        expected: Int = 201,
    ): String = post(
        "/api/joint-boxes", token,
        """{"code":"JB-${uniq().uppercase()}","name":"JB uji","location":{"longitude":$lon,"latitude":$lat},
            "trayCount":2,"capacity":$capacity}""",
        expected,
    )

    /** Kabel [type] dari [from] ke [to] beserta rute lurus di antara keduanya. */
    @Suppress("LongParameterList")
    private fun newCable(
        token: String,
        type: String,
        fromKind: String,
        fromId: String,
        toKind: String,
        toId: String,
        startLon: Double,
        endLon: Double,
        cores: Int = 12,
    ): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Ruas uji","cableType":"$type","coreCount":$cores,
                "route":[{"longitude":$startLon,"latitude":-6.24},{"longitude":$endLon,"latitude":-6.24}],
                "fromKind":"$fromKind","fromId":"$fromId","toKind":"$toKind","toId":"$toId"}""",
        ),
    )

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""

    private fun connect(token: String, jb: String, a: String, b: String, expected: Int = 201): String =
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"JOINT_BOX","closureId":"$jb","a":$a,"b":$b}""",
            expected,
        )

    @Test
    fun `dua haspel bertemu di joint box dan seratnya nyambung terus`() {
        val token = newTenantAdmin("haspel")
        val odc = newOdc(token, 106.99, -6.24)
        val jb = idOf(newJointBox(token, 107.00, -6.24))
        val odp = newOdp(token, 107.01, -6.24)

        // Satu jalur, dua kabel: haspel pertama berhenti di JB, haspel kedua lanjut.
        val hulu = newCable(token, "DISTRIBUTION", "ODC", odc, "JOINT_BOX", jb, 106.99, 107.00)
        val hilir = newCable(token, "DISTRIBUTION", "JOINT_BOX", jb, "ODP", odp, 107.00, 107.01)

        // Penomoran core tak harus sama di kedua kabel — justru itu gunanya JB:
        // ia yang memegang pemetaan core 3 haspel pertama menjadi core 1 berikutnya.
        val sambungan = connect(token, jb, core(coreId(token, hulu, 3)), core(coreId(token, hilir, 1)))
        assertThat(JsonPath.read<String>(sambungan, "$.a.label")).contains("Core 3")
        assertThat(JsonPath.read<String>(sambungan, "$.b.label")).contains("Core 1")

        val isi = getJson("/api/fiber-connections?closureKind=JOINT_BOX&closureId=$jb", token)
        assertThat(JsonPath.read<List<*>>(isi, "$.connections")).hasSize(1)

        // Kedua serat jadi terpakai; sisanya masih perawan di kedua haspel.
        assertThat(JsonPath.read<String>(getJson("/api/cables/$hulu/cores", token), "$.cores[2].status"))
            .isEqualTo("USED")
        assertThat(JsonPath.read<String>(getJson("/api/cables/$hilir/cores", token), "$.cores[0].status"))
            .isEqualTo("USED")
        assertThat(JsonPath.read<String>(getJson("/api/cables/$hilir/cores", token), "$.cores[1].status"))
            .isEqualTo("FREE")

        // ODP di balik sambungan haspel tetap tercatat di bawah ODC-nya.
        assertThat(JsonPath.read<String>(getJson("/api/odps/$odp", token), "$.odcId")).isEqualTo(odc)
    }

    @Test
    fun `persimpangan - satu kabel induk pecah jadi dua cabang di joint box`() {
        val token = newTenantAdmin("simpang")
        val odc = newOdc(token, 106.99, -6.24)
        val jb = idOf(newJointBox(token, 107.00, -6.24))
        val kiri = newOdp(token, 107.01, -6.25)
        val kanan = newOdp(token, 107.01, -6.23)

        val induk = newCable(token, "DISTRIBUTION", "ODC", odc, "JOINT_BOX", jb, 106.99, 107.00, cores = 12)
        val cabangKiri = newCable(token, "DISTRIBUTION", "JOINT_BOX", jb, "ODP", kiri, 107.00, 107.01, cores = 6)
        val cabangKanan = newCable(token, "DISTRIBUTION", "JOINT_BOX", jb, "ODP", kanan, 107.00, 107.01, cores = 6)

        // Core 1-6 induk belok kiri, 7-12 belok kanan. Tiap cabang menomori
        // core-nya dari 1 lagi — dan JB inilah satu-satunya yang tahu pemetaannya.
        (1..6).forEach { n ->
            connect(token, jb, core(coreId(token, induk, n)), core(coreId(token, cabangKiri, n)))
        }
        (1..6).forEach { n ->
            connect(token, jb, core(coreId(token, induk, n + 6)), core(coreId(token, cabangKanan, n)))
        }

        val isi = getJson("/api/fiber-connections?closureKind=JOINT_BOX&closureId=$jb", token)
        assertThat(JsonPath.read<List<*>>(isi, "$.connections")).hasSize(12)
        assertThat(JsonPath.read<Number>(getJson("/api/joint-boxes/$jb", token), "$.spliceCount").toLong())
            .isEqualTo(12L)
    }

    @Test
    fun `joint box tak berisi splitter, jadi kaki splitter ditolak`() {
        val token = newTenantAdmin("nosplit")
        val odc = newOdc(token, 106.99, -6.24)
        val jb = idOf(newJointBox(token, 107.00, -6.24))
        val kabel = newCable(token, "DISTRIBUTION", "ODC", odc, "JOINT_BOX", jb, 106.99, 107.00)

        val error = connect(
            token, jb,
            core(coreId(token, kabel, 1)),
            """{"kind":"SPLITTER_IN","nodeId":"$jb"}""",
            expected = 400,
        )
        assertThat(error).contains("tak berisi splitter")
    }

    @Test
    fun `tray habis - sambungan ke-3 ditolak di kotak berkapasitas 2`() {
        val token = newTenantAdmin("penuh")
        val odc = newOdc(token, 106.99, -6.24)
        val jb = idOf(newJointBox(token, 107.00, -6.24, capacity = 2))
        val odp = newOdp(token, 107.01, -6.24)
        val hulu = newCable(token, "DISTRIBUTION", "ODC", odc, "JOINT_BOX", jb, 106.99, 107.00)
        val hilir = newCable(token, "DISTRIBUTION", "JOINT_BOX", jb, "ODP", odp, 107.00, 107.01)

        connect(token, jb, core(coreId(token, hulu, 1)), core(coreId(token, hilir, 1)))
        connect(token, jb, core(coreId(token, hulu, 2)), core(coreId(token, hilir, 2)))
        val error = connect(
            token, jb, core(coreId(token, hulu, 3)), core(coreId(token, hilir, 3)),
            expected = 409,
        )
        assertThat(error).contains("penuh")
    }

    @Test
    fun `joint box yang masih hidup tak bisa dihapus`() {
        val token = newTenantAdmin("hapusjb")
        val odc = newOdc(token, 106.99, -6.24)
        val jb = idOf(newJointBox(token, 107.00, -6.24))
        val odp = newOdp(token, 107.01, -6.24)
        val hulu = newCable(token, "DISTRIBUTION", "ODC", odc, "JOINT_BOX", jb, 106.99, 107.00)
        val hilir = newCable(token, "DISTRIBUTION", "JOINT_BOX", jb, "ODP", odp, 107.00, 107.01)
        val sambungan = idOf(connect(token, jb, core(coreId(token, hulu, 1)), core(coreId(token, hilir, 1))))

        // Masih ada sambungan di dalamnya — layanan pelanggan lewat sini.
        assertThat(deleteAt("/api/joint-boxes/$jb", token, expected = 409)).contains("sambungan")

        deleteAt("/api/fiber-connections/$sambungan", token)
        // Sambungannya sudah lepas, tapi dua kabel masih berujung di sini.
        assertThat(deleteAt("/api/joint-boxes/$jb", token, expected = 409)).contains("ujung kabel")

        deleteAt("/api/cables/$hulu", token)
        deleteAt("/api/cables/$hilir", token)
        deleteAt("/api/joint-boxes/$jb", token)
        mockMvc.perform(get("/api/joint-boxes/$jb").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `kapasitas tak bisa diturunkan di bawah isi kotaknya`() {
        val token = newTenantAdmin("kapasitas")
        val odc = newOdc(token, 106.99, -6.24)
        val jbJson = newJointBox(token, 107.00, -6.24, capacity = 12)
        val jb = idOf(jbJson)
        val kode: String = JsonPath.read(jbJson, "$.code")
        val odp = newOdp(token, 107.01, -6.24)
        val hulu = newCable(token, "DISTRIBUTION", "ODC", odc, "JOINT_BOX", jb, 106.99, 107.00)
        val hilir = newCable(token, "DISTRIBUTION", "JOINT_BOX", jb, "ODP", odp, 107.00, 107.01)
        connect(token, jb, core(coreId(token, hulu, 1)), core(coreId(token, hilir, 1)))
        connect(token, jb, core(coreId(token, hulu, 2)), core(coreId(token, hilir, 2)))

        val body = """{"code":"$kode","name":"JB uji","location":{"longitude":107.00,"latitude":-6.24},
            "trayCount":1,"capacity":1}"""
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/joint-boxes/$jb")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(409) }
    }
}
