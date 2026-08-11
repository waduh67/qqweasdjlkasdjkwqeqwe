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
 * Kabel BACKBONE: ruas antar-POP dan ring antar-kabinet.
 *
 * Yang diuji bukan sekadar "enum bertambah satu", melainkan dua hal yang selama
 * ini memaksa operator berbohong pada datanya sendiri:
 *
 * 1. Ring ODC ↔ ODC — dulu mustahil disimpan (feeder wajib berangkat dari POP),
 *    padahal ring itulah yang membuat satu kabel putus tak memadamkan cabang.
 * 2. Ruas POP ↔ POP — dulu harus dipaksa berujung di ODC bohongan.
 *
 * Dan satu penjagaan ke arah sebaliknya: backbone tak boleh dipakai untuk turun
 * tingkat POP → kabinet, sebab itu feeder, dan dua nama untuk benda yang sama
 * membuat laporan panjang kabel per jenis kehilangan artinya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CableBackboneIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val lat = -6.23

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""),
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

    private fun newSite(token: String, lon: Double): String = idOf(
        post(
            "/api/sites", token,
            """{"code":"POP-${uniq().uppercase()}","name":"POP uji",
                "location":{"longitude":$lon,"latitude":$lat}}""",
        ),
    )

    private fun newOdf(token: String, site: String, lon: Double): String = idOf(
        post(
            "/api/odfs", token,
            """{"code":"ODF-${uniq().uppercase()}","name":"ODF uji","siteId":"$site",
                "location":{"longitude":$lon,"latitude":$lat},"portCount":48}""",
        ),
    )

    private fun newOdc(token: String, lon: Double): String = idOf(
        post(
            "/api/odcs", token,
            """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji",
                "location":{"longitude":$lon,"latitude":$lat},"splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newJointBox(token: String, lon: Double): String = idOf(
        post(
            "/api/joint-boxes", token,
            """{"code":"JB-${uniq().uppercase()}","name":"JB uji",
                "location":{"longitude":$lon,"latitude":$lat},"trayCount":4,"capacity":96}""",
        ),
    )

    @Suppress("LongParameterList")
    private fun cable(
        token: String,
        type: String,
        fromKind: String,
        fromId: String,
        toKind: String,
        toId: String,
        cores: Int = 96,
        expected: Int = 201,
    ): String = post(
        "/api/cables", token,
        """{"code":"CBL-${uniq().uppercase()}","name":"Kabel uji","cableType":"$type","coreCount":$cores,
            "route":[{"longitude":106.98,"latitude":$lat},{"longitude":107.02,"latitude":$lat}],
            "fromKind":"$fromKind","fromId":"$fromId","toKind":"$toKind","toId":"$toId"}""",
        expected,
    )

    /**
     * Ring antar-kabinet: dua ODC disambung langsung supaya masing-masing punya
     * dua jalan pulang. Sebagai feeder ini ditolak — feeder tak pernah berangkat
     * dari kabinet — dan sampai kini itu berarti ring tak bisa digambar sama
     * sekali.
     */
    @Test
    fun `ring antar-kabinet tersimpan sebagai backbone, sedangkan feeder tetap menolaknya`() {
        val token = newTenantAdmin("ring")
        val odc1 = newOdc(token, 106.98)
        val odc2 = newOdc(token, 107.02)

        val ditolak = cable(token, "FEEDER", "ODC", odc1, "ODC", odc2, cores = 48, expected = 400)
        assertThat(ditolak).contains("FEEDER")

        val id = idOf(cable(token, "BACKBONE", "ODC", odc1, "ODC", odc2, cores = 48))
        val tersimpan = getJson("/api/cables/$id", token)
        assertThat(JsonPath.read<String>(tersimpan, "$.cableType")).isEqualTo("BACKBONE")

        // Ring tidak menjadikan siapa pun induk siapa pun: kabinet kedua tetap
        // menggantung pada PON port-nya sendiri, bukan pada tetangganya.
        assertThat(JsonPath.read<Any?>(getJson("/api/odcs/$odc2", token), "$.ponPortId")).isNull()
        assertThat(JsonPath.read<Any?>(getJson("/api/odcs/$odc1", token), "$.ponPortId")).isNull()
    }

    /**
     * Ruas antar-POP: dua rak terminasi di dua gedung berbeda, disambung lewat
     * kotak sambung di tengah jalan — persis seperti di lapangan, sebab haspel
     * kabel tak pernah sepanjang jarak antar-POP.
     */
    @Test
    fun `ruas antar-POP boleh menembus joint box di tengah jalan`() {
        val token = newTenantAdmin("antarpop")
        val odfA = newOdf(token, newSite(token, 106.98), 106.98)
        val odfB = newOdf(token, newSite(token, 107.02), 107.02)
        val jb = newJointBox(token, 107.0)

        cable(token, "BACKBONE", "ODF", odfA, "JOINT_BOX", jb)
        cable(token, "BACKBONE", "JOINT_BOX", jb, "ODF", odfB)
    }

    /**
     * POP → kabinet sudah punya namanya sejak dulu. Menerimanya juga sebagai
     * backbone akan membuat dua ISP dengan jaringan identik melaporkan angka
     * yang berbeda — jadi ditolak, lengkap dengan sebutan jenis yang benar.
     */
    @Test
    fun `backbone menolak turun tingkat dari POP ke kabinet dan menyebut penggantinya`() {
        val token = newTenantAdmin("turun")
        val odf = newOdf(token, newSite(token, 106.98), 106.98)
        val odc = newOdc(token, 107.02)

        val pesan = cable(token, "BACKBONE", "ODF", odf, "ODC", odc, expected = 400)
        assertThat(pesan).contains("sederajat")
        assertThat(pesan).contains("FEEDER")

        // Pasangan ujung yang sama, jenis yang benar: diterima.
        cable(token, "FEEDER", "ODF", odf, "ODC", odc, cores = 24)
    }
}
