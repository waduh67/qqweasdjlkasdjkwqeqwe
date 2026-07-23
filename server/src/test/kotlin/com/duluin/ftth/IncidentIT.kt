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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Uji mesin korelasi Phase 3: banjir alarm sejenis di bawah satu induk topologi
 * digabung menjadi satu insiden ber-akar-masalah lewat `GET /api/incidents`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

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

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private data class Chain(val odc: String, val odp: String)

    private fun buildChain(token: String): Chain {
        val s = uniq().uppercase()
        val site = id(post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""))
        val olt = id(
            post("/api/olts", token, """{"siteId":"$site","code":"OLT-$s","name":"OLT $s","vendor":"ZTE","managementIp":"10.0.0.1","snmpCommunity":"rahasia"}"""),
        )
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(
            post("/api/odcs", token, """{"code":"ODC-$s","name":"ODC $s","location":{"longitude":106.99,"latitude":-6.24},"ponPortId":"$pon","splitterRatio":"1:8","capacity":64}"""),
        )
        val odp = id(
            post("/api/odps", token, """{"code":"ODP-$s","name":"ODP $s","location":{"longitude":106.995,"latitude":-6.245},"odcId":"$odc","splitterRatio":"1:8","capacity":8}"""),
        )
        return Chain(odc, odp)
    }

    /** Mendaftar pelanggan + ONU, memasangnya ke port ODP, dan mengembalikan serial ONU-nya. */
    private fun attachOnu(token: String, odpId: String, port: Int): String {
        val s = uniq().uppercase()
        val customer = id(post("/api/customers", token, """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji","location":{"longitude":106.99,"latitude":-6.24}}"""))
        val serial = "SN-$s"
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", 200)
        return serial
    }

    private fun newCollector(token: String): String =
        JsonPath.read(post("/api/monitoring/collectors", token, """{"name":"C-${uniq()}","pollIntervalSeconds":60}"""), "$.apiKey")

    private fun reading(serial: String, status: String, rx: Double?) =
        """{"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"$status","rxPowerDbm":${rx ?: "null"},"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"${Instant.now()}"}"""

    private fun sendMetrics(apiKey: String, vararg readings: String) {
        mockMvc.perform(
            post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"batchId":"b-${uniq()}","collectedAt":"${Instant.now()}","readings":[${readings.joinToString(",")}]}"""),
        ).andExpect(status().isOk)
    }

    private fun incidents(token: String): String =
        mockMvc.perform(get("/api/incidents").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun postAction(url: String, token: String): String =
        mockMvc.perform(post(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `banjir alarm ONU di bawah satu ODC menjadi satu insiden yang tersimpan dan bisa diakui`() {
        val token = newTenantAdmin("inc")
        val chain = buildChain(token)
        val a = attachOnu(token, chain.odp, port = 1)
        val b = attachOnu(token, chain.odp, port = 2)
        val c = attachOnu(token, chain.odp, port = 3)
        val apiKey = newCollector(token)

        // Dua ONU kehilangan sinyal, satu tetap sehat.
        sendMetrics(apiKey, reading(a, "LOS", null), reading(b, "LOS", null), reading(c, "ONLINE", -21.0))

        // Korelasi (dipicu setelah commit ingestion) menyimpan SATU insiden berakar
        // ODC, bukan dua tiket terpisah.
        val json = incidents(token)
        assertThat(JsonPath.read<List<Any>>(json, "$[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(json, "$[0].rootType")).isEqualTo("ODC")
        assertThat(JsonPath.read<Int>(json, "$[0].alarmCount")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(json, "$[0].affectedCustomerCount")).isEqualTo(2)
        assertThat(JsonPath.read<String>(json, "$[0].severity")).isEqualTo("CRITICAL")
        assertThat(JsonPath.read<String>(json, "$[0].status")).isEqualTo("OPEN")
        val incidentId = JsonPath.read<String>(json, "$[0].id")

        // Detail: timeline berisi pembukaan, dan anggota alarm hidupnya dua.
        val detail = mockMvc.perform(get("/api/incidents/$incidentId").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<List<String>>(detail, "$.timeline[*].type")).contains("OPENED")
        assertThat(JsonPath.read<List<Any>>(detail, "$.members[*]")).hasSize(2)

        // Operator mengakui: tetap terbuka, tapi statusnya berubah.
        postAction("/api/incidents/$incidentId/acknowledge", token)
        assertThat(JsonPath.read<String>(incidents(token), "$[0].status")).isEqualTo("ACKNOWLEDGED")
    }

    @Test
    fun `insiden menutup sendiri saat alarm akarnya pulih`() {
        val token = newTenantAdmin("incr")
        val chain = buildChain(token)
        val a = attachOnu(token, chain.odp, port = 1)
        val b = attachOnu(token, chain.odp, port = 2)
        val apiKey = newCollector(token)

        sendMetrics(apiKey, reading(a, "LOS", null), reading(b, "LOS", null))
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).hasSize(1)

        // Fiber tersambung lagi → alarm menutup → korelasi menutup insidennya sendiri.
        sendMetrics(apiKey, reading(a, "ONLINE", -21.0), reading(b, "ONLINE", -20.0))
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).isEmpty()
    }

    @Test
    fun `tanpa alarm, tidak ada insiden`() {
        val token = newTenantAdmin("incq")
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).isEmpty()
    }
}
