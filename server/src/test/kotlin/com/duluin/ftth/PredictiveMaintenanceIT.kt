package com.duluin.ftth

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.application.service.PredictiveMaintenanceScanner
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Uji pemeliharaan prediktif: deret redaman yang memburuk pelan-pelan harus
 * memunculkan work order preventif secara otomatis, tanpa pengguna yang
 * mengangkatnya, dan tanpa menumpuk WO saat pemindaian diulang.
 *
 * Menyentuh tiga module tanpa satu pun menembus tabel milik yang lain: monitoring
 * mendeteksi tren (regresi di TimescaleDB) dan menerbitkan event, workorder
 * mendengarkannya lalu memetakan ONU → pelanggan lewat kontrak customer dan
 * mengangkat WO. ONU sehat sebagai kontrol negatif memastikan tren datar tidak
 * ikut memicu apa pun.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PredictiveMaintenanceIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    @Autowired private lateinit var collectorRepository: CollectorRepository

    @Autowired private lateinit var scanner: PredictiveMaintenanceScanner

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

    private fun postAsCollector(url: String, apiKey: String, body: String): String =
        mockMvc.perform(
            post(url).header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    /** Membuat collector, mengembalikan (apiKey, tenantId). */
    private fun newCollector(token: String): Pair<String, UUID> {
        val json = post("/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}""")
        val apiKey = JsonPath.read<String>(json, "$.apiKey")
        val collectorId = UUID.fromString(JsonPath.read(json, "$.collector.id"))
        return apiKey to collectorRepository.findById(collectorId)!!.tenantId
    }

    /** Mendaftarkan pelanggan + ONU-nya, mengembalikan (customerId, serial). */
    private fun registerOnu(token: String): Pair<String, String> {
        val suffix = uniq().uppercase()
        val customerId = JsonPath.read<String>(
            post(
                "/api/customers", token,
                """{"code":"C-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji",
                    "location":{"longitude":106.99,"latitude":-6.24}}""",
            ),
            "$.id",
        )
        // This legacy device already existed before the historical readings.
        // Preserve it as explicit legacy provenance; do not attach pre-install metrics
        // to a newly installed warehouse episode.
        com.duluin.ftth.customer.LegacyOnuTestFixture.stage(customerId, "SN-$suffix", Instant.now().minus(Duration.ofDays(3)))
        return customerId to "SN-$suffix"
    }

    private fun reading(serial: String, rx: Double, observedAt: Instant): String =
        """
        {"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"ONLINE",
         "rxPowerDbm":$rx,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,
         "observedAt":"$observedAt"}
        """.trimIndent()

    /** Tujuh sampel tetap berada dalam jendela penerimaan collector 72 jam. */
    private fun ingestSeries(apiKey: String, serial: String, rxByHoursAgo: List<Pair<Long, Double>>) {
        val now = Instant.now()
        val readings = rxByHoursAgo.map { (hoursAgo, rx) -> reading(serial, rx, now.minus(Duration.ofHours(hoursAgo))) }
        val body = """{"batchId":"batch-${uniq()}","collectedAt":"$now","readings":[${readings.joinToString(",")}]}"""
        val result = postAsCollector("/api/collector/metrics", apiKey, body)
        assertThat(JsonPath.read<Int>(result, "$.accepted")).isEqualTo(readings.size)
        assertThat(JsonPath.read<List<String>>(result, "$.unknownSerialNumbers")).isEmpty()
    }

    private fun preventiveWorkOrders(token: String): String =
        mockMvc.perform(
            get("/api/work-orders?type=PREVENTIVE&size=50").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `redaman yang memburuk memunculkan satu WO preventif dan pemindaian ulang tidak menumpuk`() {
        val slug = "pred${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Pred Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val (apiKey, tenantId) = newCollector(token)

        // Tujuh sampel menurun 0,25 dB tiap enam jam: tren tetap -1 dB/hari.
        val (degradingCustomer, degradingSerial) = registerOnu(token)
        ingestSeries(
            apiKey, degradingSerial,
            listOf(42L to -18.0, 36L to -18.25, 30L to -18.5, 24L to -18.75, 18L to -19.0, 12L to -19.25, 6L to -19.5),
        )

        // ONU sehat sebagai kontrol: redaman datar -20 dBm tidak boleh memicu WO.
        val (_, healthySerial) = registerOnu(token)
        ingestSeries(
            apiKey, healthySerial,
            listOf(42L to -20.0, 36L to -20.0, 30L to -20.0, 24L to -20.0, 18L to -20.0, 12L to -20.0, 6L to -20.0),
        )

        TenantContext.runAs(tenantId) { scanner.scan(tenantId) }

        val afterFirst = preventiveWorkOrders(token)
        assertThat(JsonPath.read<Int>(afterFirst, "$.totalElements")).isEqualTo(1)
        assertThat(JsonPath.read<String>(afterFirst, "$.content[0].type")).isEqualTo("PREVENTIVE")
        assertThat(JsonPath.read<String>(afterFirst, "$.content[0].priority")).isEqualTo("HIGH")
        assertThat(JsonPath.read<String>(afterFirst, "$.content[0].customerId")).isEqualTo(degradingCustomer)

        // Idempoten: pemindaian berikutnya tetap melihat ONU memburuk namun tidak
        // membuat WO preventif kedua untuk pelanggan yang sama.
        TenantContext.runAs(tenantId) { scanner.scan(tenantId) }
        assertThat(JsonPath.read<Int>(preventiveWorkOrders(token), "$.totalElements")).isEqualTo(1)
    }
}
