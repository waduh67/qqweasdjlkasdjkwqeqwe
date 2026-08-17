package com.duluin.ftth

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.monitoring.application.service.PppoeAlarmEvaluator
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
import java.time.Instant
import java.util.UUID

/**
 * Uji "pelanggan offline → merah di peta" lewat alarm `PPPOE_DOWN`.
 *
 * Menutup celah yang tak terlihat dari telemetri optik: ONU boleh jadi masih menyala,
 * tapi sesi PPPoE-nya putus di BRAS (salah kredensial, sesi ngadat, di-suspend router).
 * Poll BRAS hanya melaporkan sesi HIDUP — sesi yang berakhir menghilang dari `radacct`
 * tanpa ditandai offline — jadi sesi `online=true` yang basi (tak diperbarui melebihi
 * ambang) diperlakukan putus. [PppoeAlarmEvaluator] mengangkatnya jadi alarm, yang lalu
 * memerahkan marker pelanggan + kabel drop-nya di `GET /api/gis/impacted`, dan menutup
 * sendiri saat sesi segar lagi.
 *
 * Kebasian disetel lewat waktu laporan collector (`collectedAt`) — bukan poke repo —
 * sehingga jalur ingest sesi yang sebenarnya ikut teruji.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PppoeImpactedIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var pppoeAlarmEvaluator: PppoeAlarmEvaluator

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

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun postAsCollector(url: String, apiKey: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun overlay(token: String): String =
        mockMvc.perform(get("/api/gis/impacted").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    /** Satu laporan sesi PPPoE wire (contract.RadiusSessionReading) yang dilaporkan BRAS. */
    private fun sessionReading(username: String): String =
        """
        {"username":"$username","online":true,"framedIp":"10.20.30.40","nasIp":"10.0.0.1",
         "sessionId":"sess-1","callingStationId":"AA:BB:CC:DD:EE:FF","uptimeSeconds":3600,
         "inOctets":1000,"outOctets":2000}
        """.trimIndent()

    private fun bngBatch(nasId: String, collectedAt: Instant, username: String): String =
        """{"batchId":"${uniq()}","nasId":"$nasId","collectedAt":"$collectedAt",
            "sessions":[${sessionReading(username)}]}"""

    /** Menilai alarm PPPoE tenant ini di dalam konteks tenant (seperti scheduler produksi). */
    private fun evaluate(tenantId: UUID) {
        TenantContext.runAs(tenantId) { pppoeAlarmEvaluator.evaluateTenant(tenantId) }
    }

    @Test
    fun `sesi PPPoE basi memerahkan pelanggan dan kabel drop-nya, lalu pulih saat sesi segar`() {
        val slug = "pppoe${uniq()}"
        val admin = "admin@$slug.test"
        val tenantId = onboarding.onboard(OnboardTenantCommand(slug, "PPPoE Co", admin, "Admin", pass)).tenant.id
        val token = login(slug, admin)
        val s = uniq().uppercase()

        // Rantai POP → OLT → PON → ODC → ODP (perlu ODP sebagai pangkal kabel drop).
        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""),
        )
        val olt = id(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$s","name":"OLT $s","vendor":"ZTE"}""",
            ),
        )
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$s","name":"ODC $s","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val odp = id(
            post(
                "/api/odps", token,
                """{"code":"ODP-$s","name":"ODP $s","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
            ),
        )

        // Pelanggan + langganan aktif + akun PPPoE ACTIVE ber-BRAS.
        val apiKey = JsonPath.read<String>(
            post("/api/monitoring/collectors", token, """{"name":"C-$s","pollIntervalSeconds":60}"""),
            "$.apiKey",
        )
        val nasId = id(
            post(
                "/api/bng/nas", token,
                """{"name":"BRAS-$s","vendor":"MIKROTIK","address":"10.0.0.1",
                    "nasIdentifier":"bras-$s","coaSecret":null,"collectorId":null}""",
            ),
        )
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val planId = id(
            post(
                "/api/catalog/plans", token,
                """{"name":"Home $s","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val sub = id(put("/api/customers/$customer/subscription", token, """{"planId":"$planId"}"""))
        post("/api/customers/subscriptions/$sub/activate", token, "", expected = 200)
        val username = "pppoe${uniq()}"
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
        )

        // Kabel drop ODP → pelanggan (ujung hilir yang harus ikut merah saat pelanggan offline).
        post(
            "/api/cables", token,
            """{"code":"DRP-$s","name":"Drop $s","cableType":"DROP","coreCount":1,
                "route":[{"longitude":106.995,"latitude":-6.245},{"longitude":106.996,"latitude":-6.246}],
                "fromKind":"ODP","fromId":"$odp","toKind":"CUSTOMER","toId":"$customer","fromPortNumber":1}""",
        )

        // Belum ada sesi apa pun → belum ada yang merah.
        assertThat(JsonPath.read<List<String>>(overlay(token), "$.nodes[*].id")).isEmpty()

        // Sesi dilaporkan online TAPI dengan waktu 10 menit lampau → basi (ambang 3 mnt).
        postAsCollector(
            "/api/collector/bng-sessions", apiKey,
            bngBatch(nasId, Instant.now().minusSeconds(600), username),
        )
        evaluate(tenantId)

        // Pelanggan + kabel drop-nya kini merah, penyebabnya PPPOE_DOWN (WARNING).
        val down = overlay(token)
        assertThat(JsonPath.read<List<String>>(down, "$.nodes[*].id")).contains(customer)
        assertThat(JsonPath.read<List<String>>(down, "$.nodes[?(@.id=='$customer')].severity")).containsExactly("WARNING")
        assertThat(JsonPath.read<List<String>>(down, "$.cables[*].cableType")).contains("DROP")
        assertThat(JsonPath.read<List<String>>(down, "$.cables[*].causes[*].kind")).contains("PPPOE_DOWN")
        // Hanya kabel drop yang ikut merah — ODP/ODC/OLT di hulu TIDAK menjalar dari satu
        // pelanggan offline (bukan gangguan hulu). Marker perangkat tak ikut merah.
        assertThat(JsonPath.read<List<String>>(down, "$.nodes[*].id")).doesNotContain(odp, odc, olt)

        // Sesi dilaporkan segar (waktu kini) → sesi hidup lagi, alarm menutup sendiri.
        postAsCollector(
            "/api/collector/bng-sessions", apiKey,
            bngBatch(nasId, Instant.now(), username),
        )
        evaluate(tenantId)

        val recovered = overlay(token)
        assertThat(JsonPath.read<List<String>>(recovered, "$.nodes[*].id")).doesNotContain(customer)
        assertThat(JsonPath.read<List<String>>(recovered, "$.cables[*].cableType")).doesNotContain("DROP")
    }
}
