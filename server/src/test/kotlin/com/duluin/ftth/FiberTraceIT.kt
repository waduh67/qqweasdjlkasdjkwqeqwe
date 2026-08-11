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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Telusur jalur serat OLT→pelanggan beserta anggaran redamannya.
 *
 * Yang diuji di sini bukan sebuah query, melainkan sebuah KLAIM: bahwa data
 * sambungan yang dicatat potongan demi potongan — core, splicing, splitter,
 * port ODF — benar-benar cukup untuk merangkai rantai fisik yang utuh, tanpa
 * satu pun kolom "kabel dari A ke B" yang dulu dipakai menebak-nebak.
 *
 * Karena itu skenario induknya sengaja dibuat panjang dan tak disederhanakan:
 * rak POP, patchcord, feeder, splitter kabinet, kabel distribusi, sampai kaki
 * masuk splitter ODP. Jalur sependek "ODC langsung ke ODP" akan lulus tanpa
 * membuktikan apa pun.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FiberTraceIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

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

    // ------------------------------------------------------------------
    // Bahan uji — semuanya berjajar di satu garis lintang supaya jarak antar
    // simpul bisa dihitung di kepala saat angkanya nanti diperiksa.
    // ------------------------------------------------------------------

    private val lat = -6.24

    private fun newSite(token: String): String = idOf(
        post(
            "/api/sites", token,
            """{"code":"POP-${uniq().uppercase()}","name":"POP uji",
                "location":{"longitude":106.98,"latitude":$lat}}""",
        ),
    )

    private fun newOlt(token: String, site: String): String = idOf(
        post("/api/olts", token, """{"siteId":"$site","code":"OLT-${uniq().uppercase()}","name":"OLT uji","vendor":"ZTE"}"""),
    )

    private fun newPonPort(token: String, olt: String, label: String): String =
        idOf(post("/api/olts/$olt/pon-ports", token, """{"label":"$label"}"""))

    private fun newOdf(token: String, site: String): String = idOf(
        post(
            "/api/odfs", token,
            """{"code":"ODF-${uniq().uppercase()}","name":"ODF uji","siteId":"$site",
                "location":{"longitude":106.98,"latitude":$lat},"portCount":12}""",
        ),
    )

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
        type: String,
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
            """{"name":"Kabel uji","cableType":"$type","coreCount":$cores,
                "route":[{"longitude":$fromLon,"latitude":$lat},{"longitude":$toLon,"latitude":$lat}],
                "fromKind":"$fromKind","fromId":"$fromId","toKind":"$toKind","toId":"$toId"}""",
        ),
    )

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""
    private fun splitterIn(id: String) = """{"kind":"SPLITTER_IN","nodeId":"$id"}"""
    private fun splitterOut(id: String, leg: Int) = """{"kind":"SPLITTER_OUT","nodeId":"$id","portNumber":$leg}"""
    private fun odfPort(id: String, port: Int, side: String) =
        """{"kind":"ODF_PORT","nodeId":"$id","portNumber":$port,"portSide":"$side"}"""

    private fun ponPort(id: String) = """{"kind":"PON_PORT","nodeId":"$id"}"""

    private fun connect(token: String, closure: String, closureId: String, a: String, b: String, extra: String = "") =
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"$closure","closureId":"$closureId","a":$a,"b":$b$extra}""",
        )

    /**
     * Jaringan lengkap sepanjang satu garis: rak di 106,98 → kabinet di 107,00 →
     * kotak distribusi di 107,02. Mengembalikan id ODP-nya, ujung hilir rantai.
     */
    private data class Jaringan(val odp: String, val odc: String, val odf: String, val pon: String, val feeder: String)

    private fun bangunJaringan(token: String, methodPatchcord: String = "CONNECTOR"): Jaringan {
        val site = newSite(token)
        val pon = newPonPort(token, newOlt(token, site), "1/1/1")
        val odf = newOdf(token, site)
        val odc = newOdc(token, 107.00)
        val odp = newOdp(token, 107.02)
        val feeder = newCable(token, "FEEDER", 12, "ODF", odf, 106.98, "ODC", odc, 107.00)
        val distribusi = newCable(token, "DISTRIBUTION", 8, "ODC", odc, 107.00, "ODP", odp, 107.02)
        val splOdc = splitterOf(token, "ODC", odc)

        // Di rak: core feeder dilas ke pigtail sisi belakang, lalu patchcord dari
        // sisi depan port yang sama menuju PON port.
        connect(token, "ODF", odf, core(coreId(token, feeder, 1)), odfPort(odf, 3, "BACK"))
        connect(token, "ODF", odf, odfPort(odf, 3, "FRONT"), ponPort(pon), ""","method":"$methodPatchcord"""")
        // Di kabinet: feeder masuk splitter, salah satu kakinya menyuapi core distribusi.
        connect(token, "ODC", odc, core(coreId(token, feeder, 1)), splitterIn(splOdc))
        connect(token, "ODC", odc, splitterOut(splOdc, 1), core(coreId(token, distribusi, 1)))
        // Di kotak: core distribusi masuk splitter ODP.
        connect(token, "ODP", odp, core(coreId(token, distribusi, 1)), splitterIn(splitterOf(token, "ODP", odp)))
        return Jaringan(odp, odc, odf, pon, feeder)
    }

    // ------------------------------------------------------------------

    /**
     * Rantai penuh POP→ODP terangkai dari data sambungan saja.
     *
     * Urutan hop dibaca SEARAH CAHAYA — PON port lebih dulu, kaki splitter ODP
     * paling belakang — karena itulah urutan yang dipakai orang saat membaca
     * "hilangnya di mana": mereka menyusuri dari sumber, bukan dari ujung.
     */
    @Test
    fun `jalur dari ODP tersusun sampai PON port lengkap dengan rugi kumulatifnya`() {
        val token = newTenantAdmin("telusur")
        val net = bangunJaringan(token)

        val jalur = getJson("/api/fiber-trace/closure?closureKind=ODP&closureId=${net.odp}", token)
        assertThat(JsonPath.read<List<*>>(jalur, "$")).hasSize(1)

        assertThat(JsonPath.read<String>(jalur, "$[0].end")).isEqualTo("SOURCE")
        assertThat(JsonPath.read<String>(jalur, "$[0].hops[0].kind")).isEqualTo("PON_PORT")
        assertThat(JsonPath.read<String>(jalur, "$[0].hops[0].label")).isEqualTo("PON 1/1/1")

        // Lima sambungan (2 di rak, 2 di kabinet, 1 di kotak), satu splitter,
        // dua ruas serat: feeder dan distribusi.
        assertThat(JsonPath.read<Int>(jalur, "$[0].spliceCount")).isEqualTo(5)
        assertThat(JsonPath.read<Int>(jalur, "$[0].splitterCount")).isEqualTo(1)
        val kinds = JsonPath.read<List<String>>(jalur, "$[0].hops[*].kind")
        assertThat(kinds.count { it == "FIBER" }).isEqualTo(2)
        assertThat(kinds).endsWith("SPLICE")

        // Dua ruas 0,02° bujur ≈ 2,2 km masing-masing, ditambah slack 5%.
        assertThat(JsonPath.read<Double>(jalur, "$[0].fiberMeters")).isBetween(4_000.0, 5_500.0)

        // Splitter 1:8 sendirian sudah 10,5 dB; sisanya serat + sambungan.
        val total = JsonPath.read<Double>(jalur, "$[0].totalLossDb")
        assertThat(total).isBetween(11.0, 16.0)
        assertThat(JsonPath.read<Double>(jalur, "$[0].budgetDb")).isEqualTo(28.0)
        assertThat(JsonPath.read<Double>(jalur, "$[0].marginDb")).isCloseTo(28.0 - total, within(0.02))

        // Rugi kumulatif hop terakhir = total: tak ada hop yang luput dijumlah.
        val kumulatif = JsonPath.read<List<Double>>(jalur, "$[0].hops[*].cumulativeLossDb")
        assertThat(kumulatif).isSorted()
        assertThat(kumulatif.last()).isCloseTo(total, within(0.02))

        // Belum ada satu pun hasil ukur — angkanya perkiraan, dan itu dikatakan.
        assertThat(JsonPath.read<Int>(jalur, "$[0].estimatedHops")).isEqualTo(5)
        assertThat(JsonPath.read<List<String>>(jalur, "$[0].warnings").joinToString()).contains("belum diukur")
    }

    /**
     * Telusur dari sehelai core: kedua ujungnya sama-sama tersambung, jadi arah
     * hulunya tak bisa ditebak dari datanya sendiri — harus dicoba. Ini bentuk
     * yang dipakai saat orang mengklik satu core di meja kerja splicing.
     */
    @Test
    fun `telusur dari core memilih ujung yang bermuara di OLT`() {
        val token = newTenantAdmin("core")
        val net = bangunJaringan(token)
        val coreFeeder = coreId(token, net.feeder, 1)

        val jalur = getJson("/api/fiber-trace/point?kind=CORE&coreId=$coreFeeder", token)

        assertThat(JsonPath.read<String>(jalur, "$.end")).isEqualTo("SOURCE")
        assertThat(JsonPath.read<String>(jalur, "$.startLabel")).contains("core 1")
        // Dari core feeder ke atas cuma ada rak: dua sambungan, tanpa splitter.
        assertThat(JsonPath.read<Int>(jalur, "$.splitterCount")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(jalur, "$.spliceCount")).isEqualTo(2)
        assertThat(JsonPath.read<String>(jalur, "$.hops[0].kind")).isEqualTo("PON_PORT")
    }

    /**
     * Jalur yang belum tersambung adalah TEMUAN, bukan kegagalan — dan justru
     * itu yang dicari orang saat sebuah ODP gelap padahal baru dipasang.
     */
    @Test
    fun `kotak yang belum tersambung dilaporkan buntu, bukan galat`() {
        val token = newTenantAdmin("buntu")
        val odc = newOdc(token, 107.00)
        val odp = newOdp(token, 107.02)
        newCable(token, "DISTRIBUTION", 8, "ODC", odc, 107.00, "ODP", odp, 107.02)

        val jalur = getJson("/api/fiber-trace/closure?closureKind=ODP&closureId=$odp", token)

        assertThat(JsonPath.read<String>(jalur, "$[0].end")).isEqualTo("DEAD_END")
        assertThat(JsonPath.read<List<*>>(jalur, "$[0].hops")).isEmpty()
        assertThat(JsonPath.read<List<String>>(jalur, "$[0].warnings").joinToString()).contains("tak sampai ke OLT")
    }

    /**
     * Anggaran redaman baru berguna kalau ia BERBUNYI sebelum pelanggannya
     * menelepon. Splitter bertingkat — 1:8 di kabinet, 1:8 di kotak, lalu 1:2
     * membelah satu drop untuk dua unit — menghabiskan hampir 27 dB dari jatah
     * 28 dB. Jalur begini menyala hari ini dan padam begitu satu konektor kotor.
     *
     * Diukur dari KAKI splitter terakhir, bukan dari kaki masuknya: rugi sisipan
     * sebuah modul baru dibayar cahaya setelah ia melewatinya, dan menghitungnya
     * lebih awal akan membuat setiap jalur terlihat lebih boros dari kenyataan.
     */
    @Test
    fun `splitter bertingkat memicu peringatan sisa anggaran`() {
        val token = newTenantAdmin("mepet")
        val net = bangunJaringan(token)
        val splOdp = splitterOf(token, "ODP", net.odp)
        val kedua = idOf(
            post(
                "/api/splitters", token,
                """{"ownerKind":"ODP","ownerId":"${net.odp}","code":"SPL-2","ratio":"1:2"}""",
            ),
        )
        connect(token, "ODP", net.odp, splitterOut(splOdp, 1), splitterIn(kedua))

        val jalur = getJson("/api/fiber-trace/point?kind=SPLITTER_OUT&nodeId=$kedua&portNumber=1", token)

        assertThat(JsonPath.read<String>(jalur, "$.end")).isEqualTo("SOURCE")
        assertThat(JsonPath.read<Int>(jalur, "$.splitterCount")).isEqualTo(3)
        assertThat(JsonPath.read<Double>(jalur, "$.totalLossDb")).isGreaterThan(26.0)
        assertThat(JsonPath.read<Double>(jalur, "$.marginDb")).isBetween(0.0, 3.0)
        assertThat(JsonPath.read<List<String>>(jalur, "$.warnings").joinToString()).contains("Sisa anggaran")
    }
}
