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

    private fun postAsCollector(url: String, apiKey: String, body: String) {
        mockMvc.perform(
            post(url).header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)
    }

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
        post("/api/customers/$customerId/onus", token, """{"serialNumber":"SN-$suffix"}""")
        return customerId to "SN-$suffix"
    }

    private fun reading(serial: String, rx: Double, observedAt: Instant): String =
        """
        {"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"ONLINE",
         "rxPowerDbm":$rx,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,
         "observedAt":"$observedAt"}
        """.trimIndent()

    /** Mengirim deret redaman satu ONU sepanjang beberapa hari terakhir. */
    private fun ingestSeries(apiKey: String, serial: String, rxByDaysAgo: List<Pair<Long, Double>>) {
        val now = Instant.now()
        val readings = rxByDaysAgo.map { (daysAgo, rx) -> reading(serial, rx, now.minus(Duration.ofDays(daysAgo))) }
        val body = """{"batchId":"batch-${uniq()}","collectedAt":"$now","readings":[${readings.joinToString(",")}]}"""
        postAsCollector("/api/collector/metrics", apiKey, body)
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

        // ONU yang memburuk: -18 dBm tujuh hari lalu meluncur ke -24 dBm kemarin (~ -1 dB/hari).
        val (degradingCustomer, degradingSerial) = registerOnu(token)
        ingestSeries(
            apiKey, degradingSerial,
            listOf(7L to -18.0, 6L to -19.0, 5L to -20.0, 4L to -21.0, 3L to -22.0, 2L to -23.0, 1L to -24.0),
        )

        // ONU sehat sebagai kontrol: redaman datar -20 dBm tidak boleh memicu WO.
        val (_, healthySerial) = registerOnu(token)
        ingestSeries(
            apiKey, healthySerial,
            listOf(7L to -20.0, 6L to -20.0, 5L to -20.0, 4L to -20.0, 3L to -20.0, 2L to -20.0, 1L to -20.0),
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
