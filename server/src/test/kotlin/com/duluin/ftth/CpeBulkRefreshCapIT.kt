package com.duluin.ftth

import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncScheduler
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Dua rem "Segarkan Batch" yang hanya bisa dibuktikan dengan konfigurasi lain:
 * PLAFON jumlah perangkat, dan MEMOISASI probe kesehatan.
 *
 * Berkelas sendiri — bukan karena rapi, tapi karena `@TestPropertySource` membuat
 * Spring membangun context terpisah dan properti itu tak bisa dibalik per-test di
 * dalam context bersama yang dipakai [CpeIT].
 *
 * `ftth.cpe.health-probe-ttl` sengaja dihidupkan di sini (di profil test ia `PT0S`)
 * justru supaya memoisasinya teruji tanpa membuat tes-tes lain saling mewarisi hasil
 * probe basi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "ftth.cpe.bulk-refresh-max=2",
        "ftth.cpe.health-probe-ttl=PT30S",
    ],
)
class CpeBulkRefreshCapIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var acs: InMemoryAcsGateway
    @Autowired private lateinit var scheduler: CpeSyncScheduler

    private val pass = "secret12345"

    @BeforeEach
    fun clean() = acs.reset()

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
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

    /** Pelanggan + ONU + device ACS online, dengan waktu inform yang bisa diatur. */
    private fun onlineDevice(token: String, informAgoSeconds: Long) {
        val s = uniq().uppercase()
        val customer = JsonPath.read<String>(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
            "$.id",
        )
        post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$s"}""")
        acs.seedDevice(
            AcsDevice(
                genieacsId = "genie-${uniq()}",
                serialNumber = "SN-$s",
                oui = "00AABB",
                productClass = "F670L",
                manufacturer = "ZTE",
                model = "F670L",
                softwareVersion = "V1.0.10",
                ipAddress = "100.64.0.5",
                lastInformAt = Instant.now().minusSeconds(informAgoSeconds),
            ),
        )
    }

    @Test
    fun `segarkan batch berhenti di plafon dan melaporkan sisanya dengan jujur`() {
        val token = newTenantAdmin("acs-cap")
        // Empat perangkat online, plafon dua: yang paling lama tak inform didahulukan.
        listOf(600L, 300L, 120L, 30L).forEach { onlineDevice(token, it) }
        scheduler.syncAll()

        val result = post("/api/cpe/acs/refresh-all", token, "", expected = 200)

        assertThat(JsonPath.read<Int>(result, "$.candidates")).isEqualTo(4)
        assertThat(JsonPath.read<Int>(result, "$.attempted")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(result, "$.skipped")).isEqualTo(2)
        assertThat(acs.connectionRequests).hasSize(2)
        // Pesannya harus mengaku berplafon — pada armada 3.000 ONT, tombol yang diam-diam
        // menyentuh 50 lalu berkata "selesai" akan dilaporkan sebagai fitur rusak.
        assertThat(JsonPath.read<String>(result, "$.message")).contains("klik lagi untuk melanjutkan")
    }

    @Test
    fun `probe kesehatan dimemoisasi, ACS yang mati tak langsung terlihat`() {
        val token = newTenantAdmin("acs-memo")

        assertThat(JsonPath.read<String>(getJson("/api/cpe/acs/health", token), "$.status")).isEqualTo("ONLINE")

        // ACS mati SESUDAH probe pertama tersimpan. Dalam 30 detik jawabannya tetap yang
        // lama — itulah yang menahan ruang kerja berisi sepuluh tab agar tak menghujani NBI.
        acs.failing = true
        assertThat(JsonPath.read<String>(getJson("/api/cpe/acs/health", token), "$.status")).isEqualTo("ONLINE")
    }
}
