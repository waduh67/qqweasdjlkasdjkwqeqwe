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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Pindah serat satu langkah: helai yang putus diganti helai cadangan di selubung
 * yang sama.
 *
 * Yang diuji bukan sekadar "core-nya berganti", melainkan bahwa pekerjaannya
 * TETAP SATU pekerjaan: baris sambungannya bertahan (id, pelaksana, tiket), kedua
 * ujung di kotak yang berbeda ikut berpindah berbarengan, dan serat lama
 * ditinggalkan bertanda rusak supaya orang berikutnya tak memakainya lagi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpliceCoreMoveIT {

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

    private fun putJson(url: String, token: String, body: String): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun newWorkOrder(token: String, title: String = "Perbaikan serat putus"): String =
        idOf(post("/api/work-orders", token, """{"type":"REPAIR","title":"$title"}"""))

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

    private fun newDistribution(token: String, odc: String, odpEnd: String, endLon: Double): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Distribusi utara","cableType":"DISTRIBUTION","coreCount":8,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":$endLon,"latitude":-6.24}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odpEnd"}""",
        ),
    )

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun coreStatus(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].status")

    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""
    private fun splitterIn(splitterId: String) = """{"kind":"SPLITTER_IN","nodeId":"$splitterId"}"""
    private fun splitterOut(splitterId: String, leg: Int) =
        """{"kind":"SPLITTER_OUT","nodeId":"$splitterId","portNumber":$leg}"""

    private fun connect(token: String, closure: String, closureId: String, a: String, b: String, extra: String = "") =
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"$closure","closureId":"$closureId","a":$a,"b":$b$extra}""",
        )

    private fun move(token: String, body: String, expected: Int = 200): String =
        post("/api/fiber-connections/move-core", token, body, expected)

    private fun connectionsIn(token: String, closure: String, closureId: String): String =
        getJson("/api/fiber-connections?closureKind=$closure&closureId=$closureId", token)

    private fun timeline(token: String, workOrder: String): List<String> =
        JsonPath.read(getJson("/api/work-orders/$workOrder", token), "$.timeline[*].message")

    /**
     * Sehelai serat menyalurkan satu ODP: ujung ODC-nya di kaki splitter kabinet,
     * ujung ODP-nya di input splitter. Sekali pindah, KEDUANYA ikut — inilah yang
     * paling gampang tertinggal separuh kalau dikerjakan manual.
     */
    @Test
    fun `pindah core mengangkat kedua ujungnya sekaligus dan menandai serat lama rusak`() {
        val token = newTenantAdmin("pindah")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val rusak = coreId(token, cable, 1)
        val cadangan = coreId(token, cable, 2)

        val hilir = idOf(
            connect(
                token, "ODP", odp, core(rusak), splitterIn(splitterOf(token, "ODP", odp)),
                ""","lossDb":0.08""",
            ),
        )
        connect(token, "ODC", odc, core(rusak), splitterOut(splitterOf(token, "ODC", odc), 1))

        val hasil = move(
            token,
            """{"fromCoreId":"$rusak","toCoreId":"$cadangan","reason":"Putus kena galian"}""",
        )

        assertThat(JsonPath.read<List<*>>(hasil, "$.movedConnections")).hasSize(2)
        // Serat lama ditinggalkan bertanda rusak beserta alasannya — bukan dihapus:
        // helainya masih ada di dalam selubung, dan orang berikutnya harus tahu.
        assertThat(JsonPath.read<String>(hasil, "$.fromCore.status")).isEqualTo("DAMAGED")
        assertThat(JsonPath.read<String>(hasil, "$.fromCore.note")).isEqualTo("Putus kena galian")
        assertThat(JsonPath.read<String>(hasil, "$.toCore.status")).isEqualTo("USED")
        assertThat(coreStatus(token, cable, 1)).isEqualTo("DAMAGED")
        assertThat(coreStatus(token, cable, 2)).isEqualTo("USED")

        // Barisnya BERTAHAN: id, pelaksana, dan kotaknya sama — yang berganti cuma
        // seratnya. Hasil ukur lama ikut dibersihkan sebab angkanya milik serat lama.
        val diOdp = connectionsIn(token, "ODP", odp)
        assertThat(JsonPath.read<List<*>>(diOdp, "$.connections")).hasSize(1)
        assertThat(JsonPath.read<String>(diOdp, "$.connections[0].id")).isEqualTo(hilir)
        assertThat(JsonPath.read<Int>(diOdp, "$.connections[0].a.coreNumber")).isEqualTo(2)
        assertThat(JsonPath.read<Any?>(diOdp, "$.connections[0].lossDb")).isNull()
        assertThat(JsonPath.read<String>(diOdp, "$.connections[0].splicedByName")).isEqualTo("Admin")

        // Ujung seberangnya pun sudah menunjuk helai baru, tanpa disentuh terpisah.
        assertThat(JsonPath.read<Int>(connectionsIn(token, "ODC", odc), "$.connections[0].a.coreNumber"))
            .isEqualTo(2)
    }

    /** Tiga tawaran yang harus ditolak sebelum satu baris pun tersentuh. */
    @Test
    fun `menolak core tujuan yang tak bebas, core asal yang kosong, dan kabel yang berbeda`() {
        val token = newTenantAdmin("tolak")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val odpLain = newOdp(token, 107.01, -6.245)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val cableLain = newDistribution(token, odc, odpLain, endLon = 107.01)
        val terpakai = coreId(token, cable, 1)
        connect(token, "ODP", odp, core(terpakai), splitterIn(splitterOf(token, "ODP", odp)))

        // Helai yang sudah dibooking rencana lain: ditolak, bukan diserobot diam-diam.
        putJson("/api/cables/$cable/cores", token, """{"coreNumbers":[3],"status":"RESERVED"}""")
        move(token, """{"fromCoreId":"$terpakai","toCoreId":"${coreId(token, cable, 3)}"}""", expected = 409)

        // Helai yang belum menyalurkan apa-apa tak perlu "dipindah" — cukup diubah
        // statusnya; menyediakan dua jalan untuk hal yang sama cuma membingungkan.
        move(
            token,
            """{"fromCoreId":"${coreId(token, cable, 4)}","toCoreId":"${coreId(token, cable, 5)}"}""",
            expected = 409,
        )

        // Pindah ke selubung lain berarti membangun jalur baru, bukan mengganti serat.
        move(
            token,
            """{"fromCoreId":"$terpakai","toCoreId":"${coreId(token, cableLain, 2)}"}""",
            expected = 400,
        )

        // Tak satu pun penolakan di atas boleh meninggalkan bekas.
        assertThat(coreStatus(token, cable, 1)).isEqualTo("USED")
        assertThat(JsonPath.read<Int>(connectionsIn(token, "ODP", odp), "$.connections[0].a.coreNumber"))
            .isEqualTo(1)
    }

    /**
     * Pemindahan yang dibukukan ke tiket perbaikan: linimasanya dapat satu baris
     * yang bisa dibaca orang, sambungan yang belum bertiket ikut menempel — tapi
     * yang sudah punya tiket sendiri tak dirampas.
     */
    @Test
    fun `pindah core menempel ke work order tanpa merampas tiket yang sudah ada`() {
        val token = newTenantAdmin("tiketpindah")
        val perbaikan = newWorkOrder(token)
        val pembangunan = newWorkOrder(token, "Pembangunan klaster utara")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val rusak = coreId(token, cable, 1)

        connect(token, "ODP", odp, core(rusak), splitterIn(splitterOf(token, "ODP", odp)))
        connect(
            token, "ODC", odc, core(rusak), splitterOut(splitterOf(token, "ODC", odc), 1),
            ""","workOrderId":"$pembangunan"""",
        )

        move(
            token,
            """{"fromCoreId":"$rusak","toCoreId":"${coreId(token, cable, 2)}",
                "workOrderId":"$perbaikan","reason":"Redaman jeblok"}""",
        )

        // Yang tadinya tanpa tiket kini dibukukan ke tiket perbaikan…
        assertThat(JsonPath.read<String>(connectionsIn(token, "ODP", odp), "$.connections[0].workOrderId"))
            .isEqualTo(perbaikan)
        // …sementara yang lahir dari WO pembangunan tetap menunjuk ke sana: di situlah
        // jawaban "jalur ini dulu dipasang dalam rangka apa" masih tersimpan.
        assertThat(JsonPath.read<String>(connectionsIn(token, "ODC", odc), "$.connections[0].workOrderId"))
            .isEqualTo(pembangunan)

        val jejak = timeline(token, perbaikan)
        assertThat(jejak).anyMatch { it.contains("dipindah") && it.contains("Redaman jeblok") }
        assertThat(jejak.count { it.contains("dipindah") }).isEqualTo(1)
    }

    /** Tiket yang tak ada ditolak sebelum seratnya berpindah. */
    @Test
    fun `work order hantu membatalkan pemindahan`() {
        val token = newTenantAdmin("hantu")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val rusak = coreId(token, cable, 1)
        connect(token, "ODP", odp, core(rusak), splitterIn(splitterOf(token, "ODP", odp)))

        move(
            token,
            """{"fromCoreId":"$rusak","toCoreId":"${coreId(token, cable, 2)}","workOrderId":"${UUID.randomUUID()}"}""",
            expected = 404,
        )
        assertThat(coreStatus(token, cable, 1)).isEqualTo("USED")
        assertThat(coreStatus(token, cable, 2)).isEqualTo("FREE")
    }
}
