package com.duluin.ftth

import com.duluin.ftth.contract.CollectorProtocol
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
import java.time.Instant
import java.util.UUID

/**
 * Uji ambang alarm per tenant.
 *
 * Yang dijaga di sini bukan sekadar "setelan tersimpan", melainkan janji yang
 * dipegang layarnya: ambang yang digeser LANGSUNG berlaku pada alarm yang sudah
 * terbuka. Operator menggeser ambang justru karena melihat alarm yang menurutnya
 * berlebihan; kalau alarm itu baru berubah setelah siklus polling berikutnya — atau
 * tak pernah, untuk jenis yang dimatikan — orang akan menyimpulkan setelannya rusak
 * dan berhenti memakainya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlarmRuleIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

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

    /** Mendaftarkan pelanggan + ONU, mengembalikan serial ONU-nya. */
    private fun registerOnu(token: String): String {
        val s = uniq().uppercase()
        val customer = JsonPath.read<String>(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                    "location":{"longitude":106.99,"latitude":-6.24}}""",
            ),
            "$.id",
        )
        post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$s"}""")
        return "SN-$s"
    }

    private fun newCollectorKey(token: String): String = JsonPath.read(
        post("/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}"""),
        "$.apiKey",
    )

    /** Melaporkan satu bacaan redaman dari collector. */
    private fun report(apiKey: String, serial: String, rxPowerDbm: Double) {
        mockMvc.perform(
            post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"batchId":"${uniq()}","collectedAt":"${Instant.now()}","readings":[
                        {"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"ONLINE",
                         "rxPowerDbm":$rxPowerDbm,"txPowerDbm":null,"uptimeSeconds":null,
                         "distanceMeters":null,"observedAt":"${Instant.now()}"}]}""",
                ),
        ).andExpect(status().isOk)
    }

    private fun rules(token: String): String =
        mockMvc.perform(get("/api/monitoring/alarm-rules").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun setRule(token: String, kind: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put("/api/monitoring/alarm-rules/$kind").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    /** Alarm terbuka jenis tertentu beserta keparahannya. */
    private fun openSeverities(token: String, kind: String): List<String> {
        val json = mockMvc.perform(
            get("/api/monitoring/alarms?status=ACTIVE&size=50").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.content[?(@.kind=='$kind')].severity")
    }

    @Test
    fun `daftar ambang memuat semua jenis walau tenant belum pernah menyetel apa pun`() {
        val token = newTenantAdmin("rulelist")
        val json = rules(token)

        // Tabel aturan tenant baru masih kosong, tapi pemantauannya sudah jalan pakai
        // bawaan — layarnya wajib mengatakan itu, bukan "belum ada aturan".
        val kinds: List<String> = JsonPath.read(json, "$[*].kind")
        assertThat(kinds).contains("ONU_LOS", "ONU_LOW_RX", "ONU_HIGH_RX", "COLLECTOR_SILENT")
        assertThat(JsonPath.read<List<Boolean>>(json, "$[*].customised")).doesNotContain(true)

        val high = "$[?(@.kind=='ONU_HIGH_RX')]"
        assertThat(JsonPath.read<List<Double>>(json, "$high.warningThreshold")).containsExactly(-8.0)
        assertThat(JsonPath.read<List<Double>>(json, "$high.criticalThreshold")).containsExactly(-5.0)
        assertThat(JsonPath.read<List<String>>(json, "$high.direction")).containsExactly("HIGHER_IS_WORSE")
        assertThat(JsonPath.read<List<String>>(json, "$high.unit")).containsExactly("dBm")
        assertThat(JsonPath.read<List<String>>(json, "$high.guidance")[0]).isNotBlank()

        // Jenis biner tak punya ambang untuk digeser — layarnya harus tahu itu.
        assertThat(JsonPath.read<List<String>>(json, "$[?(@.kind=='ONU_LOS')].direction")).containsExactly(null)
    }

    @Test
    fun `melonggarkan ambang langsung menurunkan kelas alarm yang sudah terbuka`() {
        val token = newTenantAdmin("rulewide")
        val apiKey = newCollectorKey(token)
        val serial = registerOnu(token)

        // -3,55 dBm: penerima ONU jenuh. Dengan bawaan (-8 peringatan, -5 kritis) ini kritis.
        report(apiKey, serial, -3.55)
        assertThat(openSeverities(token, "ONU_HIGH_RX")).containsExactly("CRITICAL")

        // ISP ini sadar ONU-ONU ujinya memang dekat OLT dan menggeser ambangnya.
        val updated = setRule(
            token, "ONU_HIGH_RX",
            """{"enabled":true,"warningThreshold":-6.0,"criticalThreshold":-3.0}""",
        )
        assertThat(JsonPath.read<Boolean>(updated, "$.customised")).isTrue()
        assertThat(JsonPath.read<Int>(updated, "$.openAlarmCount")).isEqualTo(1)

        // Tanpa menunggu siklus polling berikutnya: alarm yang sama kini peringatan.
        assertThat(openSeverities(token, "ONU_HIGH_RX")).containsExactly("WARNING")

        // Dimatikan sama sekali → alarmnya ditutup, bukan digantung selamanya.
        setRule(token, "ONU_HIGH_RX", """{"enabled":false,"warningThreshold":-6.0,"criticalThreshold":-3.0}""")
        assertThat(openSeverities(token, "ONU_HIGH_RX")).isEmpty()

        // Bacaan seburuk apa pun tak lagi memunculkan alarm selama jenis itu mati.
        report(apiKey, serial, -2.0)
        assertThat(openSeverities(token, "ONU_HIGH_RX")).isEmpty()

        // Dikembalikan ke bawaan → jenisnya hidup lagi dan bacaan yang sama kembali kritis.
        mockMvc.perform(
            delete("/api/monitoring/alarm-rules/ONU_HIGH_RX").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
        assertThat(JsonPath.read<List<Boolean>>(rules(token), "$[?(@.kind=='ONU_HIGH_RX')].customised"))
            .containsExactly(false)
        report(apiKey, serial, -3.55)
        assertThat(openSeverities(token, "ONU_HIGH_RX")).containsExactly("CRITICAL")
    }

    @Test
    fun `ambang yang tak mungkin terpicu ditolak, bukan diterima diam-diam`() {
        val token = newTenantAdmin("rulebad")

        // Makin besar makin buruk, tapi kritisnya dipasang lebih rendah dari peringatan:
        // alarm kritisnya tak akan pernah terpicu.
        setRule(
            token, "ONU_HIGH_RX",
            """{"enabled":true,"warningThreshold":-3.0,"criticalThreshold":-6.0}""",
            expected = 400,
        )

        // Menyalakan jenis berambang tanpa satu pun ambang = memantau tanpa ukuran.
        setRule(
            token, "ONU_LOW_RX",
            """{"enabled":true,"warningThreshold":null,"criticalThreshold":null}""",
            expected = 400,
        )

        // Setelan yang ditolak tidak boleh meninggalkan jejak setengah jadi.
        assertThat(JsonPath.read<List<Boolean>>(rules(token), "$[*].customised")).doesNotContain(true)
    }

    @Test
    fun `setelan satu tenant tidak mengubah ambang tenant lain`() {
        val a = newTenantAdmin("ruleiso")
        val b = newTenantAdmin("ruleiso")

        setRule(a, "ONU_LOW_RX", """{"enabled":true,"warningThreshold":-26.5,"criticalThreshold":-28.0}""")

        assertThat(JsonPath.read<List<Double>>(rules(a), "$[?(@.kind=='ONU_LOW_RX')].warningThreshold"))
            .containsExactly(-26.5)
        assertThat(JsonPath.read<List<Double>>(rules(b), "$[?(@.kind=='ONU_LOW_RX')].warningThreshold"))
            .containsExactly(-25.0)
        assertThat(JsonPath.read<List<Boolean>>(rules(b), "$[?(@.kind=='ONU_LOW_RX')].customised"))
            .containsExactly(false)
    }
}
