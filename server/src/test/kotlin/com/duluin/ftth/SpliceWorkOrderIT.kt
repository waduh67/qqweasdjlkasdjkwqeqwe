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
 * Sambungan serat nyantol ke work order & orang yang mengerjakannya.
 *
 * Sampai kini sebuah baris sambungan cuma bisa menjawab "apa tersambung ke apa".
 * Dua pertanyaan yang justru paling sering diajukan penyelia tak terjawab: "yang
 * di ODP-12 ini pekerjaan siapa?" dan "work order kemarin isinya apa saja?".
 * Berkas ini menguji keduanya dari ujung ke ujung — termasuk bahwa jejaknya
 * TETAP ADA di linimasa tiket meski sambungannya belakangan dilepas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpliceWorkOrderIT {

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

    private fun deleteAt(url: String, token: String) {
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
    }

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun newWorkOrder(token: String, title: String = "Pasang klaster utara"): String =
        idOf(post("/api/work-orders", token, """{"type":"PSB","title":"$title"}"""))

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

    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    private fun core(id: String) = """{"kind":"CORE","coreId":"$id"}"""
    private fun splitterIn(splitterId: String) = """{"kind":"SPLITTER_IN","nodeId":"$splitterId"}"""
    private fun splitterOut(splitterId: String, leg: Int) =
        """{"kind":"SPLITTER_OUT","nodeId":"$splitterId","portNumber":$leg}"""

    private fun connectBody(closure: String, closureId: String, a: String, b: String, extra: String = "") =
        """{"closureKind":"$closure","closureId":"$closureId","a":$a,"b":$b$extra}"""

    private fun timeline(token: String, workOrder: String): List<String> =
        JsonPath.read(getJson("/api/work-orders/$workOrder", token), "$.timeline[*].message")

    /**
     * Satu tiket, dua kotak dibuka, jejaknya terbaca dua arah: dari sambungan
     * ("ini pekerjaan siapa, untuk tiket apa") dan dari tiket ("isinya apa saja").
     */
    @Test
    fun `sambungan membawa nama tiket dan nama teknisinya, dua arah`() {
        val token = newTenantAdmin("wo")
        val wo = newWorkOrder(token)
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val core1 = coreId(token, cable, 1)

        val hilir = post(
            "/api/fiber-connections", token,
            connectBody("ODP", odp, core(core1), splitterIn(splitterOf(token, "ODP", odp)), ""","workOrderId":"$wo""""),
        )
        post(
            "/api/fiber-connections", token,
            connectBody("ODC", odc, core(core1), splitterOut(splitterOf(token, "ODC", odc), 1), ""","workOrderId":"$wo""""),
        )

        // Dari sisi sambungan: kode tiket & nama pelasnya, bukan sekadar UUID —
        // yang berdiri di depan kotak butuh nama, bukan kunci basis data.
        assertThat(JsonPath.read<String>(hilir, "$.workOrderId")).isEqualTo(wo)
        assertThat(JsonPath.read<String>(hilir, "$.workOrderCode")).startsWith("WO-")
        assertThat(JsonPath.read<String>(hilir, "$.splicedByName")).isEqualTo("Admin")
        assertThat(JsonPath.read<String>(hilir, "$.splicedAt")).isNotBlank()

        // Dari sisi tiket: dikelompokkan per KOTAK, sebab begitulah kerjanya
        // berlangsung — didatangi, dibuka, dikerjakan, lalu pindah.
        val pekerjaan = getJson("/api/fiber-connections/by-work-order?workOrderId=$wo", token)
        assertThat(JsonPath.read<List<*>>(pekerjaan, "$")).hasSize(2)
        assertThat(JsonPath.read<List<*>>(pekerjaan, "$[0].connections")).hasSize(1)
        assertThat(JsonPath.read<List<*>>(pekerjaan, "$[1].connections")).hasSize(1)
        assertThat(pekerjaan).contains("ODC-", "ODP-")

        // Linimasa tiketnya ikut bertambah, dengan kalimat yang bisa dibaca orang.
        assertThat(timeline(token, wo)).anyMatch { it.startsWith("Serat disambung di ODP-") }
    }

    /** Kerja rutin tanpa tiket tetap sah — sambungannya cuma tak punya kode WO. */
    @Test
    fun `sambungan tanpa work order tetap tercatat siapa yang mengerjakannya`() {
        val token = newTenantAdmin("rutin")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)

        val tanpaTiket = post(
            "/api/fiber-connections", token,
            connectBody("ODP", odp, core(coreId(token, cable, 1)), splitterIn(splitterOf(token, "ODP", odp))),
        )
        assertThat(JsonPath.read<Any?>(tanpaTiket, "$.workOrderId")).isNull()
        assertThat(JsonPath.read<Any?>(tanpaTiket, "$.workOrderCode")).isNull()
        // Pelakunya tetap tercatat: yang opsional tiketnya, bukan pertanggungjawabannya.
        assertThat(JsonPath.read<String>(tanpaTiket, "$.splicedByName")).isEqualTo("Admin")
    }

    /**
     * Dokumentasi yang menyusul dari kantor: sambungan yang tadinya tanpa tiket
     * dibukukan belakangan. Menempel sekali, dan tak bisa dipindah ke tiket lain
     * — memindahkannya diam-diam menghapus jejak siapa mengerjakan apa.
     */
    @Test
    fun `work order boleh ditempelkan menyusul, tapi tak boleh dipindah`() {
        val token = newTenantAdmin("susul")
        val wo = newWorkOrder(token)
        val woLain = newWorkOrder(token, "Perbaikan klaster selatan")
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)

        val id = idOf(
            post(
                "/api/fiber-connections", token,
                connectBody("ODP", odp, core(coreId(token, cable, 1)), splitterIn(splitterOf(token, "ODP", odp))),
            ),
        )

        val ditempel = putJson(
            "/api/fiber-connections/$id", token,
            """{"method":"FUSION","lossDb":0.09,"workOrderId":"$wo"}""",
        )
        assertThat(JsonPath.read<String>(ditempel, "$.workOrderId")).isEqualTo(wo)
        assertThat(timeline(token, wo)).anyMatch { it.contains("dibukukan ke work order ini") }

        // Hasil ukur yang menyusul lagi tak menambah baris linimasa yang sama.
        putJson("/api/fiber-connections/$id", token, """{"method":"FUSION","lossDb":0.11,"workOrderId":"$wo"}""")
        assertThat(timeline(token, wo).count { it.contains("dibukukan ke work order ini") }).isEqualTo(1)

        // Pindah tiket: ditolak.
        mockMvc.perform(
            put("/api/fiber-connections/$id").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"method":"FUSION","workOrderId":"$woLain"}"""),
        ).andExpect(status().isConflict)

        // Tiket yang tak ada ditolak sebelum seratnya tercatat tersambung.
        post(
            "/api/fiber-connections", token,
            connectBody(
                "ODP", odp, core(coreId(token, cable, 2)),
                splitterOut(splitterOf(token, "ODP", odp), 1),
                ""","workOrderId":"${UUID.randomUUID()}"""",
            ),
            expected = 404,
        )
    }

    /**
     * Sambungan yang dilepas hilang dari tabel — tapi tiketnya harus tetap ingat
     * pernah ada. Tanpa ini, WO yang seratnya belakangan dilepas terbaca seolah
     * tak pernah disentuh siapa pun.
     */
    @Test
    fun `melepas sambungan meninggalkan jejaknya di linimasa tiket`() {
        val token = newTenantAdmin("lepas")
        val wo = newWorkOrder(token)
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)

        val id = idOf(
            post(
                "/api/fiber-connections", token,
                connectBody(
                    "ODP", odp, core(coreId(token, cable, 1)),
                    splitterIn(splitterOf(token, "ODP", odp)), ""","workOrderId":"$wo"""",
                ),
            ),
        )
        deleteAt("/api/fiber-connections/$id", token)

        assertThat(JsonPath.read<List<*>>(getJson("/api/fiber-connections/by-work-order?workOrderId=$wo", token), "$"))
            .isEmpty()
        assertThat(timeline(token, wo)).anyMatch { it.startsWith("Sambungan di ODP-") && it.contains("dilepas") }
    }

    /** Satu batch "sambung 1:1 otomatis" = satu kali kotak dibuka = satu tiket. */
    @Test
    fun `sambung sekaligus membukukan seluruh pasangannya ke satu tiket`() {
        val token = newTenantAdmin("borong")
        val wo = newWorkOrder(token)
        val odc = newOdc(token, 106.99, -6.24)
        val odp = newOdp(token, 107.005, -6.24)
        val cable = newDistribution(token, odc, odp, endLon = 107.005)
        val spl = splitterOf(token, "ODC", odc)
        fun pair(coreNumber: Int, leg: Int) =
            """{"a":${core(coreId(token, cable, coreNumber))},"b":${splitterOut(spl, leg)}}"""

        val jadi = post(
            "/api/fiber-connections/bulk", token,
            """{"closureKind":"ODC","closureId":"$odc","workOrderId":"$wo",
                "pairs":[${pair(1, 1)},${pair(2, 2)},${pair(3, 3)}]}""",
        )
        assertThat(JsonPath.read<List<*>>(jadi, "$")).hasSize(3)

        val pekerjaan = getJson("/api/fiber-connections/by-work-order?workOrderId=$wo", token)
        // Tiga sambungan, SATU kotak — daftarnya menceritakan itu apa adanya.
        assertThat(JsonPath.read<List<*>>(pekerjaan, "$")).hasSize(1)
        assertThat(JsonPath.read<List<*>>(pekerjaan, "$[0].connections")).hasSize(3)
    }
}
