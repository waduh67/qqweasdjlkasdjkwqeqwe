package com.duluin.ftth

import com.duluin.ftth.contract.CollectorProtocol
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
class IncidentIT : com.duluin.ftth.customer.WarehouseRegisteredOnuFixture() {

    @Autowired private lateinit var mockMvc: MockMvc
    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun newTenantAdmin(prefix: String): String = tenant("$prefix${uniq()}")
    private val oltCodes = mutableMapOf<String, String>()

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
        oltCodes[token] = "OLT-$s"
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
    private fun attachOnu(token: String, odpId: String, port: Int): String =
        attachOnuReturningCustomer(token, odpId, port).second

    /** Seperti [attachOnu] tetapi juga mengembalikan id pelanggan pemilik ONU. */
    private fun attachOnuReturningCustomer(token: String, odpId: String, port: Int): Pair<String, String> {
        val s = uniq().uppercase()
        val customer = id(post("/api/customers", token, """{"areaId":"${area(token)}","code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji","location":{"longitude":106.99,"latitude":-6.24}}"""))
        val serial = "SN-$s"
        val onu = registerWarehouseOnu(token, customer, "$serial")
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", 200)
        return customer to serial
    }

    /** Pelanggan polos tanpa ONU/pasangan jaringan — tak akan pernah terdampak insiden topologi. */
    private fun bareCustomer(token: String): String {
        val s = uniq().uppercase()
        return id(post("/api/customers", token, """{"areaId":"${area(token)}","code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji","location":{"longitude":106.99,"latitude":-6.24}}"""))
    }

    private fun newCollector(token: String): String =
        JsonPath.read(post("/api/monitoring/collectors", token, """{"name":"C-${uniq()}","pollIntervalSeconds":60}"""), "$.apiKey")

    private fun reading(token: String, serial: String, status: String, rx: Double?) =
        """{"serialNumber":"$serial","oltCode":"${oltCodes.getValue(token)}","ponPortLabel":"1/1/1","status":"$status","rxPowerDbm":${rx ?: "null"},"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"${Instant.now()}"}"""

    /** Bacaan dengan sebab putus dari register OLT — bahan korelasi mati-listrik vs fiber-putus. */
    private fun readingCause(token: String, serial: String, status: String, cause: String) =
        """{"serialNumber":"$serial","oltCode":"${oltCodes.getValue(token)}","ponPortLabel":"1/1/1","status":"$status","rxPowerDbm":null,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"${Instant.now()}","lastDownCause":"$cause"}"""

    private fun sendMetrics(apiKey: String, vararg readings: String) {
        val result = mockMvc.perform(
            post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"batchId":"b-${uniq()}","collectedAt":"${Instant.now()}","readings":[${readings.joinToString(",")}]}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<Int>(result, "$.accepted")).isEqualTo(readings.size)
        assertThat(JsonPath.read<List<String>>(result, "$.unknownSerialNumbers")).isEmpty()
    }

    private fun incidents(token: String): String =
        mockMvc.perform(get("/api/incidents").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun incidentsForCustomer(token: String, customerId: String): String =
        mockMvc.perform(get("/api/incidents?customerId=$customerId").header("Authorization", "Bearer $token"))
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
        sendMetrics(apiKey, reading(token, a, "LOS", null), reading(token, b, "LOS", null), reading(token, c, "ONLINE", -21.0))

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

        sendMetrics(apiKey, reading(token, a, "LOS", null), reading(token, b, "LOS", null))
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).hasSize(1)

        // Fiber tersambung lagi → alarm menutup → korelasi menutup insidennya sendiri.
        sendMetrics(apiKey, reading(token, a, "ONLINE", -21.0), reading(token, b, "ONLINE", -20.0))
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).isEmpty()
    }

    @Test
    fun `blast-radius dying-gasp ditandai dugaan mati listrik area, bukan fiber putus`() {
        val token = newTenantAdmin("pln")
        val chain = buildChain(token)
        val a = attachOnu(token, chain.odp, port = 1)
        val b = attachOnu(token, chain.odp, port = 2)
        val c = attachOnu(token, chain.odp, port = 3)
        val apiKey = newCollector(token)

        // Tiga ONU padam serentak dan OLT melaporkan dying-gasp: ciri PLN mati di
        // area, bukan fiber putus — tindakan operatornya berbeda (tunggu listrik
        // pulih, bukan kirim teknisi cari kabel).
        sendMetrics(
            apiKey,
            readingCause(token, a, "OFFLINE", "DYING_GASP"),
            readingCause(token, b, "OFFLINE", "DYING_GASP"),
            readingCause(token, c, "OFFLINE", "DYING_GASP"),
        )

        val json = incidents(token)
        assertThat(JsonPath.read<List<Any>>(json, "$[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(json, "$[0].rootType")).isEqualTo("ODC")
        assertThat(JsonPath.read<String>(json, "$[0].suspectedCause")).isEqualTo("POWER_OUTAGE")

        // Detail membawa sebab per-ONU juga, biar operator lihat dasarnya.
        val incidentId = JsonPath.read<String>(json, "$[0].id")
        val detail = mockMvc.perform(get("/api/incidents/$incidentId").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<List<String>>(detail, "$.members[*].downCause")).contains("DYING_GASP")
    }

    @Test
    fun `blast-radius LOS ditandai dugaan fiber putus`() {
        val token = newTenantAdmin("cut")
        val chain = buildChain(token)
        val a = attachOnu(token, chain.odp, port = 1)
        val b = attachOnu(token, chain.odp, port = 2)
        val apiKey = newCollector(token)

        // Dua ONU LOS serentak — sinyal hilang total, register bukan dying-gasp:
        // pola fiber putus, kirim teknisi.
        sendMetrics(apiKey, readingCause(token, a, "LOS", "LOS"), readingCause(token, b, "LOS", "LOS"))

        val json = incidents(token)
        assertThat(JsonPath.read<List<Any>>(json, "$[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(json, "$[0].suspectedCause")).isEqualTo("FIBER_CUT")
    }

    @Test
    fun `tanpa alarm, tidak ada insiden`() {
        val token = newTenantAdmin("incq")
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).isEmpty()
    }

    @Test
    fun `insiden aktif tersaring hanya ke pelanggan yang terdampak`() {
        val token = newTenantAdmin("inccust")
        val chain = buildChain(token)
        val (custA, serialA) = attachOnuReturningCustomer(token, chain.odp, port = 1)
        val (custB, serialB) = attachOnuReturningCustomer(token, chain.odp, port = 2)
        val custC = bareCustomer(token) // tak terpasang di ODP terdampak → tak pernah terdampak
        val apiKey = newCollector(token)

        // Dua ONU se-ODP LOS → satu insiden berakar ODC yang berdampak ke A & B.
        sendMetrics(apiKey, reading(token, serialA, "LOS", null), reading(token, serialB, "LOS", null))
        assertThat(JsonPath.read<List<Any>>(incidents(token), "$[*]")).hasSize(1)

        // Pelanggan terdampak melihat persis insiden itu.
        val forA = incidentsForCustomer(token, custA)
        assertThat(JsonPath.read<List<Any>>(forA, "$[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(forA, "$[0].rootType")).isEqualTo("ODC")
        assertThat(JsonPath.read<List<Any>>(incidentsForCustomer(token, custB), "$[*]")).hasSize(1)

        // Pelanggan tak terdampak tak melihat apa-apa.
        assertThat(JsonPath.read<List<Any>>(incidentsForCustomer(token, custC), "$[*]")).isEmpty()
    }
}
