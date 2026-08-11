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
 * Sambungan serat — inti desain ulang perkabelan.
 *
 * Skenario induk di sini adalah yang selama ini tak bisa dicatat: SATU kabel
 * distribusi 8 core ditarik dari ODC melewati beberapa ODP, dan tiap ODP cuma
 * "memakan" satu core lewat mid-span tapping. Dulu itu memaksa operator
 * menggambar kabel palsu ODC→ODP satu per satu; sekarang kabelnya tetap satu
 * baris dan yang tersambung adalah seratnya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FiberConnectionIT {

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

    private fun deleteAt(url: String, token: String, expected: Int = 204) {
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
    }

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

    /** Kabel distribusi 8 core dari [odc] menuju [odpEnd] — melewati ODP di antaranya. */
    private fun newDistribution(token: String, odc: String, odpEnd: String, endLon: Double): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Distribusi utara","cableType":"DISTRIBUTION","coreCount":8,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":$endLon,"latitude":-6.24}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odpEnd"}""",
        ),
    )

    /** Id core bernomor [number] pada kabel. */
    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun coreStatus(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].status")

    private fun connectBody(closure: String, closureId: String, a: String, b: String, extra: String = "") =
        """{"closureKind":"$closure","closureId":"$closureId","a":$a,"b":$b$extra}"""

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""

    /**
     * Modul splitter di dalam sebuah kabinet. Yang ditunjuk titik sambung adalah
     * MODULNYA, bukan kabinetnya: satu ODC boleh berisi beberapa modul dengan
     * rasio berbeda, jadi "kaki 3" baru punya arti setelah jelas kaki modul mana.
     */
    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    private fun splitterIn(splitterId: String) = """{"kind":"SPLITTER_IN","nodeId":"$splitterId"}"""
    private fun splitterOut(splitterId: String, leg: Int) =
        """{"kind":"SPLITTER_OUT","nodeId":"$splitterId","portNumber":$leg}"""

    private fun odfPort(nodeId: String, port: Int, side: String) =
        """{"kind":"ODF_PORT","nodeId":"$nodeId","portNumber":$port,"portSide":"$side"}"""

    private fun ponPort(id: String) = """{"kind":"PON_PORT","nodeId":"$id"}"""

    private fun newSite(token: String): String = idOf(
        post(
            "/api/sites", token,
            """{"code":"POP-${uniq().uppercase()}","name":"POP uji",
                "location":{"longitude":106.98,"latitude":-6.23}}""",
        ),
    )

    private fun newOlt(token: String, site: String): String = idOf(
        post(
            "/api/olts", token,
            """{"siteId":"$site","code":"OLT-${uniq().uppercase()}","name":"OLT uji","vendor":"ZTE"}""",
        ),
    )

    private fun newPonPort(token: String, olt: String, label: String): String =
        idOf(post("/api/olts/$olt/pon-ports", token, """{"label":"$label"}"""))

    private fun newOdf(token: String, site: String, portCount: Int): String = idOf(
        post(
            "/api/odfs", token,
            """{"code":"ODF-${uniq().uppercase()}","name":"ODF uji","siteId":"$site",
                "location":{"longitude":106.98,"latitude":-6.23},"portCount":$portCount}""",
        ),
    )

    /** Feeder yang BERANGKAT dari rak menuju ODC — ujung hulunya rak, bukan badan OLT. */
    private fun newFeeder(token: String, odf: String, odc: String): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Feeder barat","cableType":"FEEDER","coreCount":12,
                "route":[{"longitude":106.98,"latitude":-6.23},{"longitude":107.02,"latitude":-6.24}],
                "fromKind":"ODF","fromId":"$odf","toKind":"ODC","toId":"$odc"}""",
        ),
    )

    @Test
    fun `satu kabel melewati beberapa ODP dan tiap ODP cuma memakan satu core`() {
        val token = newTenantAdmin("tap")
        val odc = newOdc(token, 106.99, -6.24)
        // Tiga ODP berjajar di garis yang sama; kabel berujung di yang terjauh.
        val odp1 = newOdp(token, 106.995, -6.24)
        val odp2 = newOdp(token, 107.000, -6.24)
        val odp3 = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp3, endLon = 107.005)

        // ODP-1 dan ODP-2 bukan ujung kabel — mereka dilewati di tengah jalur.
        // Dulu ini butuh kabel palsu sendiri-sendiri; sekarang cukup satu serat.
        post(token = token, url = "/api/fiber-connections", body = connectBody("ODP", odp1, core(coreId(token, cable, 1)), splitterIn(splitterOf(token, "ODP", odp1))))
        post(token = token, url = "/api/fiber-connections", body = connectBody("ODP", odp2, core(coreId(token, cable, 2)), splitterIn(splitterOf(token, "ODP", odp2))))

        // Ujung hulu core 1 disambung ke kaki splitter ODC — sehelai serat memang
        // punya DUA ujung, dan keduanya sah karena closure-nya berbeda.
        val hulu = post(
            "/api/fiber-connections", token,
            connectBody("ODC", odc, core(coreId(token, cable, 1)), splitterOut(splitterOf(token, "ODC", odc), 1), ""","method":"FUSION","lossDb":0.08"""),
        )
        assertThat(JsonPath.read<Double>(hulu, "$.lossDb")).isEqualTo(0.08)
        assertThat(JsonPath.read<String>(hulu, "$.methodLabel")).isEqualTo("Fusion (las)")

        // Yang habis cuma dua core dari delapan — sisanya lewat terus, utuh.
        assertThat(coreStatus(token, cable, 1)).isEqualTo("USED")
        assertThat(coreStatus(token, cable, 2)).isEqualTo("USED")
        assertThat(coreStatus(token, cable, 3)).isEqualTo("FREE")

        val isiOdp1 = getJson("/api/fiber-connections?closureKind=ODP&closureId=$odp1", token)
        assertThat(JsonPath.read<List<*>>(isiOdp1, "$.connections")).hasSize(1)
        // Teknisi menyebut serat lewat warna & kabelnya, bukan lewat UUID.
        assertThat(JsonPath.read<String>(isiOdp1, "$.connections[0].a.label")).contains("Core 1", "Biru")
        assertThat(JsonPath.read<String>(isiOdp1, "$.connections[0].b.label")).isEqualTo("Input splitter")
    }

    @Test
    fun `satu titik tak bisa dipakai dua sambungan`() {
        val token = newTenantAdmin("rebut")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val core1 = coreId(token, cable, 1)
        val core2 = coreId(token, cable, 2)
        val spl = splitterOf(token, "ODP", odp)

        post("/api/fiber-connections", token, connectBody("ODP", odp, core(core1), splitterIn(spl)))

        // Core yang sama, closure yang sama: inilah "satu core dijual ke dua pelanggan".
        post("/api/fiber-connections", token, connectBody("ODP", odp, core(core1), splitterOut(spl, 1)), expected = 409)
        // Kaki masuk splitter juga cuma satu — serat lain tak bisa ikut menempel.
        post("/api/fiber-connections", token, connectBody("ODP", odp, core(core2), splitterIn(spl)), expected = 409)
    }

    @Test
    fun `serat yang tak lewat closure ditolak, lengkap dengan jaraknya`() {
        val token = newTenantAdmin("jauh")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        // ODP di kecamatan sebelah — kabelnya jelas tak lewat sini.
        val nyasar = newOdp(token, 107.10, -6.31)

        val error = post(
            "/api/fiber-connections", token,
            connectBody("ODP", nyasar, core(coreId(token, cable, 1)), splitterIn(splitterOf(token, "ODP", nyasar))),
            expected = 400,
        )
        assertThat(error).contains("tak lewat")
    }

    @Test
    fun `bentuk titik yang mustahil ditolak sebelum menyentuh basis data`() {
        val token = newTenantAdmin("bentuk")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val core1 = coreId(token, cable, 1)
        val spl = splitterOf(token, "ODP", odp)

        // Titik core tanpa core-nya.
        post("/api/fiber-connections", token, connectBody("ODP", odp, """{"kind":"CORE"}""", splitterIn(spl)), expected = 400)
        // Kaki splitter di luar kapasitas 1:8.
        post("/api/fiber-connections", token, connectBody("ODP", odp, core(core1), splitterOut(spl, 99)), expected = 400)
        // Modul milik kabinet sebelah — salah pilih di layar, bukan splitter kotak ini.
        post(
            "/api/fiber-connections", token,
            connectBody("ODP", odp, core(core1), splitterIn(splitterOf(token, "ODC", odc))),
            expected = 400,
        )
        // Closure ODF yang id-nya ternyata ODP: raknya memang tak ada.
        post("/api/fiber-connections", token, connectBody("ODF", odp, core(core1), splitterIn(spl)), expected = 404)
        // Port ODF di dalam ODP — rak tak bisa dibawa-bawa ke kotak distribusi.
        post("/api/fiber-connections", token, connectBody("ODP", odp, core(core1), odfPort(odp, 1, "BACK")), expected = 400)
    }

    /**
     * Perjalanan penuh sehelai serat di dalam POP, dan alasan ODF ada sama sekali:
     * kabel outdoor BERHENTI di rak, seratnya dilas ke pigtail di sisi belakang
     * port, lalu dari sisi depan port itu patchcord ditarik ke PON port OLT.
     * Satu adapter, dua sambungan — dan keduanya pekerjaan yang berbeda.
     */
    @Test
    fun `feeder berhenti di rak, lalu patchcord melanjutkannya ke PON port`() {
        val token = newTenantAdmin("rak")
        val site = newSite(token)
        val pon = newPonPort(token, newOlt(token, site), "1/1/1")
        val odf = newOdf(token, site, portCount = 12)
        val odc = newOdc(token, 107.02, -6.24)
        val feeder = newFeeder(token, odf, odc)
        val core1 = coreId(token, feeder, 1)

        // Belakang: core kabel luar dilas ke pigtail.
        post("/api/fiber-connections", token, connectBody("ODF", odf, core(core1), odfPort(odf, 3, "BACK")))
        // Depan port yang SAMA: patchcord ke OLT. Bukan tabrakan — sisinya beda.
        post("/api/fiber-connections", token, connectBody("ODF", odf, odfPort(odf, 3, "FRONT"), ponPort(pon)))

        val isi = getJson("/api/fiber-connections?closureKind=ODF&closureId=$odf", token)
        assertThat(JsonPath.read<List<*>>(isi, "$.connections")).hasSize(2)
        assertThat(isi).contains("PON 1/1/1")

        // Sisi yang sama dua kali tetap haram.
        val bentrok = post(
            "/api/fiber-connections", token,
            connectBody("ODF", odf, core(coreId(token, feeder, 2)), odfPort(odf, 3, "BACK")),
            expected = 409,
        )
        assertThat(bentrok).contains("sudah dipakai")

        // Port di luar rak 12-port adalah salah ketik, bukan port yang belum dipasang.
        post(
            "/api/fiber-connections", token,
            connectBody("ODF", odf, core(coreId(token, feeder, 2)), odfPort(odf, 99, "BACK")),
            expected = 400,
        )
    }

    /**
     * Sisi bukan label yang boleh ditukar-tukar: kabel outdoor tak berkonektor,
     * jadi ia tak pernah nyantol di sisi depan; dan PON port tak pernah dilas ke
     * core, ia selalu lewat patchcord.
     */
    @Test
    fun `sisi port menentukan apa yang boleh menempel padanya`() {
        val token = newTenantAdmin("sisi")
        val site = newSite(token)
        val pon = newPonPort(token, newOlt(token, site), "1/1/1")
        val odf = newOdf(token, site, portCount = 12)
        val odc = newOdc(token, 107.02, -6.24)
        val feeder = newFeeder(token, odf, odc)
        val core1 = coreId(token, feeder, 1)

        val depan = post(
            "/api/fiber-connections", token,
            connectBody("ODF", odf, core(core1), odfPort(odf, 1, "FRONT")),
            expected = 400,
        )
        assertThat(depan).contains("patchcord")

        val belakang = post(
            "/api/fiber-connections", token,
            connectBody("ODF", odf, odfPort(odf, 1, "BACK"), ponPort(pon)),
            expected = 400,
        )
        assertThat(belakang).contains("pigtail")
    }

    @Test
    fun `core baru benar-benar bebas setelah kedua ujungnya lepas`() {
        val token = newTenantAdmin("lepas")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val core1 = coreId(token, cable, 1)

        val hilir = idOf(
            post(
                "/api/fiber-connections", token,
                connectBody("ODP", odp, core(core1), splitterIn(splitterOf(token, "ODP", odp))),
            ),
        )
        val hulu = idOf(
            post(
                "/api/fiber-connections", token,
                connectBody("ODC", odc, core(core1), splitterOut(splitterOf(token, "ODC", odc), 1)),
            ),
        )

        deleteAt("/api/fiber-connections/$hilir", token)
        // Ujung satunya masih tersambung — seratnya belum bebas.
        assertThat(coreStatus(token, cable, 1)).isEqualTo("USED")

        deleteAt("/api/fiber-connections/$hulu", token)
        assertThat(coreStatus(token, cable, 1)).isEqualTo("FREE")
    }

    @Test
    fun `menghapus kabel ikut memutus sambungannya, bukan meninggalkan jalur hantu`() {
        val token = newTenantAdmin("hapus")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)

        post(
            "/api/fiber-connections", token,
            connectBody("ODP", odp, core(coreId(token, cable, 1)), splitterIn(splitterOf(token, "ODP", odp))),
        )
        deleteAt("/api/cables/$cable", token)

        val isi = getJson("/api/fiber-connections?closureKind=ODP&closureId=$odp", token)
        assertThat(JsonPath.read<List<*>>(isi, "$.connections")).isEmpty()
    }
}
