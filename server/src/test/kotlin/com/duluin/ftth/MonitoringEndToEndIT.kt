package com.duluin.ftth

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.application.service.SilentCollectorEvaluator
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
import java.time.Instant
import java.util.UUID

/**
 * Uji end-to-end Phase 2a: gerbang collector (autentikasi API key), ingestion
 * metrik, mesin alarm, peredaman banjir alarm, deduplikasi batch, dan isolasi
 * tenant untuk data monitoring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitoringEndToEndIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    @Autowired
    private lateinit var silentCollectorEvaluator: SilentCollectorEvaluator

    @Autowired
    private lateinit var collectorRepository: CollectorRepository

    private val pass = "secret12345"

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

    /** Mengirim ke gerbang collector memakai API key, bukan JWT pengguna. */
    private fun postAsCollector(url: String, apiKey: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    /** Membuat collector dan mengembalikan API key mentahnya. */
    private fun newCollector(token: String): String {
        val json = post(
            "/api/monitoring/collectors", token,
            """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}""",
        )
        return JsonPath.read(json, "$.apiKey")
    }

    /** Mendaftarkan pelanggan + ONU, lalu mengembalikan serial ONU-nya. */
    private fun registerOnu(token: String): String {
        val suffix = uniq().uppercase()
        val customer = JsonPath.read<String>(
            post(
                "/api/customers", token,
                """{"code":"C-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji",
                    "location":{"longitude":106.99,"latitude":-6.24}}""",
            ),
            "$.id",
        )
        post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$suffix"}""")
        return "SN-$suffix"
    }

    private fun reading(serial: String, status: String, rxPower: Double?): String =
        """
        {"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"$status",
         "rxPowerDbm":${rxPower ?: "null"},"txPowerDbm":null,"uptimeSeconds":null,
         "distanceMeters":null,"observedAt":"${Instant.now()}"}
        """.trimIndent()

    private fun batch(vararg readings: String, batchId: String = uniq()): String =
        """{"batchId":"$batchId","collectedAt":"${Instant.now()}","readings":[${readings.joinToString(",")}]}"""

    @Test
    fun `gerbang collector menolak tanpa API key, dengan key salah, dan dengan JWT pengguna`() {
        val token = newTenantAdmin("gate")

        mockMvc.perform(
            post("/api/collector/heartbeat").contentType(MediaType.APPLICATION_JSON)
                .content("""{"agentVersion":"0.2.0"}"""),
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/api/collector/heartbeat").header(CollectorProtocol.API_KEY_HEADER, "ftthc_bukan_kunci")
                .contentType(MediaType.APPLICATION_JSON).content("""{"agentVersion":"0.2.0"}"""),
        ).andExpect(status().isUnauthorized)

        // JWT pengguna bukan kredensial collector — keduanya sengaja tidak saling menerima.
        mockMvc.perform(
            post("/api/collector/heartbeat").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"agentVersion":"0.2.0"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `versi protokol yang tidak cocok ditolak`() {
        val apiKey = newCollector(newTenantAdmin("proto"))
        mockMvc.perform(
            post("/api/collector/heartbeat")
                .header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .header(CollectorProtocol.PROTOCOL_VERSION_HEADER, "99")
                .contentType(MediaType.APPLICATION_JSON).content("""{"agentVersion":"0.2.0"}"""),
        ).andExpect(status().isUpgradeRequired)
    }

    @Test
    fun `API key hanya muncul sekali dan tidak pernah dikembalikan lagi`() {
        val token = newTenantAdmin("key")
        val created = post(
            "/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}""",
        )
        val apiKey = JsonPath.read<String>(created, "$.apiKey")

        val list = mockMvc.perform(get("/api/monitoring/collectors").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(list).doesNotContain(apiKey)
        // Hanya petunjuk pendek yang tersimpan, agar operator bisa mencocokkan
        // collector tanpa kuncinya pernah bisa dibaca ulang.
        assertThat(JsonPath.read<String>(list, "$[0].apiKeyHint")).isEqualTo(apiKey.take(8))
    }

    @Test
    fun `serial ONU yang tidak dikenal dilaporkan sebagai perangkat liar`() {
        val apiKey = newCollector(newTenantAdmin("wild"))
        val json = postAsCollector(
            "/api/collector/metrics", apiKey,
            batch(reading("SN-TIDAK-TERDAFTAR", "ONLINE", -20.0)),
        )
        assertThat(JsonPath.read<Int>(json, "$.accepted")).isZero()
        assertThat(JsonPath.read<List<String>>(json, "$.unknownSerialNumbers")).containsExactly("SN-TIDAK-TERDAFTAR")
    }

    @Test
    fun `batch yang sama tidak dihitung dua kali`() {
        val apiKey = newCollector(newTenantAdmin("dedup"))
        val body = batch(reading("SN-APAPUN", "ONLINE", -20.0), batchId = "batch-${uniq()}")

        assertThat(JsonPath.read<Boolean>(postAsCollector("/api/collector/metrics", apiKey, body), "$.duplicate"))
            .isFalse()
        // Pengiriman ulang akibat koneksi ISP terputus tidak boleh melipatgandakan metrik.
        assertThat(JsonPath.read<Boolean>(postAsCollector("/api/collector/metrics", apiKey, body), "$.duplicate"))
            .isTrue()
    }

    @Test
    fun `LOS memicu alarm kritis dan redaman lemah memicu alarm sesuai ambang`() {
        val token = newTenantAdmin("alarm")
        val apiKey = newCollector(token)
        val losSerial = registerOnu(token)
        val weakSerial = registerOnu(token)
        val healthySerial = registerOnu(token)

        postAsCollector(
            "/api/collector/metrics", apiKey,
            batch(
                reading(losSerial, "LOS", null),
                reading(weakSerial, "ONLINE", -28.0),
                reading(healthySerial, "ONLINE", -21.0),
            ),
        )

        val alarms = mockMvc.perform(
            get("/api/monitoring/alarms?size=50").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val kinds: List<String> = JsonPath.read(alarms, "$.content[*].kind")
        assertThat(kinds).contains("ONU_LOS", "ONU_LOW_RX")
        // -28 dBm melewati ambang kritis -27 dBm.
        val severities: List<String> = JsonPath.read(alarms, "$.content[*].severity")
        assertThat(severities).contains("CRITICAL")
        // ONU sehat tidak boleh memunculkan alarm sama sekali.
        assertThat(JsonPath.read<Int>(alarms, "$.totalElements")).isEqualTo(2)
    }

    @Test
    fun `kondisi berulang memperbarui alarm yang sama, bukan menumpuk alarm baru`() {
        val token = newTenantAdmin("flood")
        val apiKey = newCollector(token)
        val serial = registerOnu(token)

        repeat(5) {
            postAsCollector("/api/collector/metrics", apiKey, batch(reading(serial, "LOS", null)))
        }

        val alarms = mockMvc.perform(
            get("/api/monitoring/alarms?size=50").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        // Satu alarm dengan hitungan kejadian naik — bukan lima baris alarm.
        assertThat(JsonPath.read<Int>(alarms, "$.totalElements")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(alarms, "$.content[0].occurrenceCount")).isEqualTo(5)
    }

    @Test
    fun `alarm menutup sendiri ketika kondisinya pulih`() {
        val token = newTenantAdmin("clear")
        val apiKey = newCollector(token)
        val serial = registerOnu(token)

        postAsCollector("/api/collector/metrics", apiKey, batch(reading(serial, "LOS", null)))
        assertThat(activeAlarmCount(token)).isEqualTo(1)

        // Fiber tersambung kembali: alarm harus menutup tanpa campur tangan operator.
        postAsCollector("/api/collector/metrics", apiKey, batch(reading(serial, "ONLINE", -21.0)))
        assertThat(activeAlarmCount(token)).isZero()
    }

    @Test
    fun `metrik dan alarm tenant lain tidak terlihat`() {
        val tokenA = newTenantAdmin("mona")
        val tokenB = newTenantAdmin("monb")
        val apiKeyA = newCollector(tokenA)
        val serial = registerOnu(tokenA)

        postAsCollector("/api/collector/metrics", apiKeyA, batch(reading(serial, "LOS", null)))

        assertThat(activeAlarmCount(tokenA)).isEqualTo(1)
        assertThat(activeAlarmCount(tokenB)).isZero()
    }

    @Test
    fun `riwayat redaman ONU tersimpan dan bisa dibaca kembali`() {
        val token = newTenantAdmin("hist")
        val apiKey = newCollector(token)
        val serial = registerOnu(token)

        repeat(3) {
            postAsCollector("/api/collector/metrics", apiKey, batch(reading(serial, "ONLINE", -22.0)))
        }

        // ONU ini sehat sehingga tidak memunculkan alarm; id-nya diambil dari
        // daftar pelanggan.
        val customers = mockMvc.perform(get("/api/customers?size=50").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val onuId = JsonPath.read<String>(customers, "$.content[0].onus[0].id")

        val history = mockMvc.perform(
            get("/api/monitoring/onus/$onuId/history?hours=24").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<List<Any>>(history, "$.points")).hasSize(3)
        assertThat(JsonPath.read<Double>(history, "$.averageRxPowerDbm")).isEqualTo(-22.0)
    }

    @Test
    fun `menghapus collector menutup alarm collector-membisu-nya agar tidak menggantung yatim`() {
        val token = newTenantAdmin("wdog")
        val created = post(
            "/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}""",
        )
        val collectorId = UUID.fromString(JsonPath.read<String>(created, "$.collector.id"))
        val tenantId = collectorRepository.findById(collectorId)!!.tenantId

        // Watchdog menandai collector berhenti melapor → alarm kritis terangkat.
        TenantContext.runAs(tenantId) {
            silentCollectorEvaluator.evaluate(tenantId, collectorId, "Collector membisu", true, Instant.now())
        }
        assertThat(activeAlarmCount(token)).isEqualTo(1)

        // Menghapus collector harus menutup alarmnya: collector yang lenyap tidak
        // akan pernah melapor lagi untuk menutupnya sendiri, sehingga tanpa ini
        // alarm kritisnya menggantung yatim di dashboard selamanya.
        mockMvc.perform(
            delete("/api/monitoring/collectors/$collectorId").header("Authorization", "Bearer $token"),
        ).andExpect(status().isNoContent)
        assertThat(activeAlarmCount(token)).isZero()
    }

    private fun activeAlarmCount(token: String): Int {
        val json = mockMvc.perform(
            get("/api/monitoring/alarms?status=ACTIVE&size=50").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.totalElements")
    }
}
