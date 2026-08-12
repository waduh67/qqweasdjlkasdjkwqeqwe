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
        // Tak ada satu pun splicing didata di sini, dan panelnya mengaku begitu
        // alih-alih menyajikan taksiran gambar sebagai kepastian.
        assertThat(JsonPath.read<Boolean>(feederCut, "$.fromSplicing")).isFalse()
        assertThat(JsonPath.read<List<String>>(feederCut, "$.warnings").joinToString()).contains("gambar kabel")

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

    /**
     * Satu selubung yang dikupas di tengah jalan: inilah bentuk yang sebenarnya
     * dipasang orang di lapangan, dan justru bentuk yang paling lama salah dibaca.
     *
     * Kabel distribusi 8 core berangkat dari kabinet dan berakhir di ODP paling
     * ujung; dua ODP di tengah bentang tak punya kabel sendiri — seratnya diambil
     * dengan mengupas selubung yang lewat di depan pintunya, satu core satu kotak.
     * Graf KABEL cuma melihat ujung hilirnya, jadi simulasi putus lama melaporkan
     * satu ODP padahal backhoe menjatuhkan ketiganya beserta pelanggan di dalamnya.
     *
     * Yang dibuktikan di sini: catatan splicing dipakai melengkapi jawaban itu,
     * sampai ke nama pelanggan yang harus ditelepon lebih dulu.
     */
    @Test
    fun `putus satu selubung menjatuhkan semua ODP yang dikupas di tengah bentang`() {
        val slug = "tap${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tap Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()
        val lat = -6.24

        // Rak POP → kabinet, supaya tiap core punya hulu yang benar-benar bermuara
        // di OLT. Tanpa itu penelusuran menolak menebak arah dan tak melaporkan apa pun.
        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":$lat}}"""),
        )
        val olt = id(post("/api/olts", token, """{"siteId":"$site","code":"OLT-$s","name":"OLT $s","vendor":"ZTE"}"""))
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odf = id(
            post(
                "/api/odfs", token,
                """{"code":"ODF-$s","name":"ODF $s","siteId":"$site",
                    "location":{"longitude":106.98,"latitude":$lat},"portCount":12}""",
            ),
        )
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$s","name":"ODC $s","location":{"longitude":107.00,"latitude":$lat},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        val odps = (1..3).map { urutan ->
            id(
                post(
                    "/api/odps", token,
                    """{"code":"ODP-$s-$urutan","name":"ODP $s-$urutan","odcId":"$odc",
                        "location":{"longitude":${107.00 + urutan * 0.01},"latitude":$lat},
                        "splitterRatio":"1:8","capacity":8}""",
                ),
            )
        }

        val feeder = id(
            post(
                "/api/cables", token,
                """{"code":"FDR-$s","name":"Feeder $s","cableType":"FEEDER","coreCount":12,
                    "route":[{"longitude":106.98,"latitude":$lat},{"longitude":107.00,"latitude":$lat}],
                    "fromKind":"ODF","fromId":"$odf","toKind":"ODC","toId":"$odc"}""",
            ),
        )
        // SATU kabel untuk tiga kotak — ujung gambarnya cuma menyentuh ODP terakhir,
        // dua kotak lainnya disinggahi di tengah bentang dan disebut apa adanya
        // di daftar singgahan. Tanpa itu tak ada yang tahu selubungnya dibuka di
        // sana, dan kotak yang ikut mati saat kabel putus akan luput dihitung.
        val selubung = id(
            post(
                "/api/cables", token,
                """{"code":"DST-$s","name":"Distribusi $s","cableType":"DISTRIBUTION","coreCount":8,
                    "route":[{"longitude":107.00,"latitude":$lat},{"longitude":107.03,"latitude":$lat}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"${odps[2]}",
                    "waypoints":[{"nodeKind":"ODP","nodeId":"${odps[0]}","role":"TAPPED"},
                                 {"nodeKind":"ODP","nodeId":"${odps[1]}","role":"TAPPED"}]}""",
            ),
        )

        // Di rak: core feeder dilas ke pigtail, patchcord dari sisi depan ke PON port.
        connect(token, "ODF", odf, core(coreId(token, feeder, 1)), """{"kind":"ODF_PORT","nodeId":"$odf","portNumber":1,"portSide":"BACK"}""")
        connect(
            token, "ODF", odf,
            """{"kind":"ODF_PORT","nodeId":"$odf","portNumber":1,"portSide":"FRONT"}""",
            """{"kind":"PON_PORT","nodeId":"$pon"}""",
        )
        // Di kabinet: feeder masuk splitter, tiga kakinya menyuapi tiga core selubung.
        val splOdc = splitterOf(token, "ODC", odc)
        connect(token, "ODC", odc, core(coreId(token, feeder, 1)), """{"kind":"SPLITTER_IN","nodeId":"$splOdc"}""")
        odps.indices.forEach { i ->
            connect(
                token, "ODC", odc,
                """{"kind":"SPLITTER_OUT","nodeId":"$splOdc","portNumber":${i + 1}}""",
                core(coreId(token, selubung, i + 1)),
            )
        }
        // Di tiap kotak: selubung yang sama dikupas, satu core diambil, sisanya lewat.
        odps.forEachIndexed { i, odp ->
            connect(
                token, "ODP", odp, core(coreId(token, selubung, i + 1)),
                """{"kind":"SPLITTER_IN","nodeId":"${splitterOf(token, "ODP", odp)}"}""",
            )
        }

        // Dua pelanggan: satu di kotak tengah bentang (yang selama ini luput), satu
        // di kotak ujung (yang sudah ketahuan sejak dulu).
        val pelangganTengah = pasangPelanggan(token, "T-$s", odps[0])
        val pelangganUjung = pasangPelanggan(token, "U-$s", odps[2])

        val putus = cutBlast(token, selubung)

        assertThat(JsonPath.read<Int>(putus, "$.odpCount")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(putus, "$.customerCount")).isEqualTo(2)
        assertThat(JsonPath.read<List<String>>(putus, "$.customers[*].customerId"))
            .containsExactlyInAnyOrder(pelangganTengah, pelangganUjung)
        // Kabinetnya sendiri tetap menyala: yang putus di hilirnya.
        assertThat(JsonPath.read<Int>(putus, "$.odcCount")).isEqualTo(0)
        // Ketiga core yang tersambung terbaca utuh sampai OLT — tak ada yang perlu diakui kurang.
        assertThat(JsonPath.read<Boolean>(putus, "$.fromSplicing")).isTrue()
        assertThat(JsonPath.read<List<*>>(putus, "$.warnings")).isEmpty()
    }

    private fun connect(token: String, closureKind: String, closureId: String, a: String, b: String) =
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"$closureKind","closureId":"$closureId","a":$a,"b":$b}""",
        )

    private fun core(coreId: String) = """{"kind":"CORE","coreId":"$coreId"}"""

    private fun coreId(token: String, cableId: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cableId/cores", token), "$.cores[${number - 1}].id")

    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun pasangPelanggan(token: String, kode: String, odpId: String): String {
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"$kode","name":"Pelanggan $kode","address":"Jl. Uji",
                    "location":{"longitude":107.01,"latitude":-6.241}}""",
            ),
        )
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$kode"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":1}""", 200)
        return customer
    }
}
