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
 * Pelanggan cabut, dalam satu langkah.
 *
 * Yang diuji di sini adalah kebiasaan lapangan, bukan sebuah endpoint: pelanggan
 * berhenti, teknisi menggulung drop-nya, lalu — kalau pencabutannya berbelit —
 * TAK ADA yang membebaskan kaki splitter di ODP. Enam bulan kemudian kotak itu
 * terlihat penuh padahal seperempat kakinya milik orang yang sudah pindah.
 *
 * Karena itu tiga hal diperiksa berdampingan: sambungannya benar-benar lepas,
 * core-nya benar-benar kembali bebas (itu yang membuat kotaknya bisa dijual
 * lagi), dan kabelnya boleh ditandai ditinggal — bukan dihapus, sebab seratnya
 * masih tergantung di tiang dan masih akan dilihat orang yang datang ke situ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DropReleaseIT {

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

    private fun newOdc(token: String): String = idOf(
        post(
            "/api/odcs", token,
            """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji","location":{"longitude":107.00,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newOdp(token: String): String = idOf(
        post(
            "/api/odps", token,
            """{"code":"ODP-${uniq().uppercase()}","name":"ODP uji","location":{"longitude":107.02,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newDrop(token: String, odp: String): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Drop uji","cableType":"DROP","coreCount":1,
                "route":[{"longitude":107.02,"latitude":$lat},{"longitude":107.021,"latitude":$lat}],
                "fromKind":"ODP","fromId":"$odp","toKind":"CUSTOMER","toId":"${UUID.randomUUID()}"}""",
        ),
    )

    private fun coreOf(token: String, cable: String): Map<String, Any> =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[0]")

    private fun splitterOf(token: String, ownerKind: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=$ownerKind&ownerId=$ownerId", token), "$.splitters[0].id")

    /** ODP berisi splitter, satu drop terpasang di kaki 1. Mengembalikan id drop-nya. */
    private fun pasangPelanggan(token: String, odp: String): String {
        val drop = newDrop(token, odp)
        val splitter = splitterOf(token, "ODP", odp)
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"ODP","closureId":"$odp",
                "a":{"kind":"SPLITTER_OUT","nodeId":"$splitter","portNumber":1},
                "b":{"kind":"CORE","coreId":"${coreOf(token, drop)["id"]}"}}""",
        )
        return drop
    }

    private fun releaseDrop(token: String, cable: String, body: String, expected: Int = 200): String =
        post("/api/cables/$cable/release-drop", token, body, expected)

    /**
     * Pencabutan penuh: sambungan lepas, core bebas, kabel ditandai ditinggal.
     *
     * Kaki splitter yang kembali kosong itulah inti perkaranya — tanpa itu,
     * seluruh tindakan ini cuma mengubah satu kata di layar.
     */
    @Test
    fun `cabut pelanggan melepas sambungan, membebaskan core, dan menandai kabelnya ditinggal`() {
        val token = newTenantAdmin("cabut")
        val odp = newOdp(token)
        val drop = pasangPelanggan(token, odp)
        assertThat(coreOf(token, drop)["status"]).isEqualTo("USED")

        val hasil = releaseDrop(token, drop, """{"abandon":true,"note":"pelanggan pindah kota"}""")

        assertThat(JsonPath.read<Int>(hasil, "$.removedConnections")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(hasil, "$.freedCores")).isEqualTo(1)
        assertThat(JsonPath.read<String>(hasil, "$.status")).isEqualTo("ABANDONED")
        assertThat(JsonPath.read<String>(hasil, "$.message")).contains("ditinggal")

        assertThat(coreOf(token, drop)["status"]).isEqualTo("FREE")
        assertThat(JsonPath.read<String>(getJson("/api/cables/$drop", token), "$.status")).isEqualTo("ABANDONED")

        // Kaki splitter ODP kembali kosong — kotak ini bisa dijual lagi.
        val sambungan = getJson("/api/fiber-connections?closureKind=ODP&closureId=$odp", token)
        assertThat(JsonPath.read<List<*>>(sambungan, "$.connections")).isEmpty()
    }

    /**
     * Rumah yang sama sering langsung berlangganan lagi atas nama penghuni baru,
     * jadi "ditinggal" TAK boleh otomatis: kabel yang masih berguna tak usah
     * dihidupkan ulang cuma karena sistem terlalu rajin.
     */
    @Test
    fun `drop yang masih akan dipakai lagi dilepas tanpa ditandai ditinggal`() {
        val token = newTenantAdmin("pakai")
        val odp = newOdp(token)
        val drop = pasangPelanggan(token, odp)

        val hasil = releaseDrop(token, drop, """{"abandon":false}""")

        assertThat(JsonPath.read<String>(hasil, "$.status")).isEqualTo("ACTIVE")
        assertThat(JsonPath.read<String>(hasil, "$.message")).contains("penghuni berikutnya")
        assertThat(coreOf(token, drop)["status"]).isEqualTo("FREE")

        // Kaki 1 benar-benar bebas: pelanggan berikutnya boleh memakainya.
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"ODP","closureId":"$odp",
                "a":{"kind":"SPLITTER_OUT","nodeId":"${splitterOf(token, "ODP", odp)}","portNumber":1},
                "b":{"kind":"CORE","coreId":"${coreOf(token, drop)["id"]}"}}""",
        )
    }

    /**
     * Ruas yang menyuapi banyak pelanggan tak boleh punya tombol "lepas semua".
     * Yang begitu memang harus dikerjakan per core, di meja sambung, oleh tangan
     * yang ragu-ragu — dan pesannya menyebutkan ke mana harus pergi.
     */
    @Test
    fun `kabel distribusi menolak dicabut sekaligus dan menunjukkan jalan yang benar`() {
        val token = newTenantAdmin("tolak")
        val odc = newOdc(token)
        val odp = newOdp(token)
        val distribusi = idOf(
            post(
                "/api/cables", token,
                """{"name":"Distribusi uji","cableType":"DISTRIBUTION","coreCount":8,
                    "route":[{"longitude":107.00,"latitude":$lat},{"longitude":107.02,"latitude":$lat}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
            ),
        )

        val galat = releaseDrop(token, distribusi, """{"abandon":true}""", expected = 400)

        assertThat(galat).contains("meja sambung")
        assertThat(JsonPath.read<String>(getJson("/api/cables/$distribusi", token), "$.status")).isEqualTo("ACTIVE")
    }
}
