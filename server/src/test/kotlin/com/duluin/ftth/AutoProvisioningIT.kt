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
 * Uji auto-provisioning ONU: serial yang dilaporkan OLT tapi belum terdaftar
 * ditangkap ke kotak masuk provisioning, lalu operator menuntaskannya menjadi
 * pelanggan terpasang tanpa mengetik ulang serial.
 *
 * Menyatukan dua module tanpa saling menyentuh tabel: monitoring menangkap serial
 * liar dari aliran ingestion, customer yang mendaftarkan & memasang ONU-nya lewat
 * `CustomerApi.provisionOnu`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoProvisioningIT {

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

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun postAsCollector(apiKey: String, body: String): String =
        mockMvc.perform(
            post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun newCollector(token: String): String =
        JsonPath.read(
            post("/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}"""),
            "$.apiKey",
        )

    private fun reading(serial: String, oltCode: String, rx: Double?, ponPortLabel: String = "1/1/1"): String =
        """
        {"serialNumber":"$serial","oltCode":"$oltCode","ponPortLabel":"$ponPortLabel","status":"UNKNOWN",
         "rxPowerDbm":${rx ?: "null"},"txPowerDbm":null,"uptimeSeconds":null,
         "distanceMeters":null,"observedAt":"${Instant.now()}"}
        """.trimIndent()

    private fun batch(vararg readings: String): String =
        """{"batchId":"batch-${uniq()}","collectedAt":"${Instant.now()}","readings":[${readings.joinToString(",")}]}"""

    /** Membangun rantai POP → OLT → PON → ODC → ODP dan mengembalikan (oltCode, odpId, customerId). */
    private fun scaffold(token: String): Triple<String, String, String> {
        val s = uniq().uppercase()
        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""),
        )
        val olt = id(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$s","name":"OLT $s","vendor":"ZTE",
                    "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}""",
            ),
        )
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$s","name":"ODC $s","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        val odp = id(
            post(
                "/api/odps", token,
                """{"code":"ODP-$s","name":"ODP $s","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji","location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        return Triple("OLT-$s", odp, customer)
    }

    private fun inbox(token: String, state: String? = null): String =
        getJson("/api/monitoring/discovered-onus" + (state?.let { "?state=$it" } ?: ""), token)

    @Test
    fun `serial tak dikenal ditangkap ke kotak masuk lalu diprovisikan jadi pelanggan terpasang`() {
        val token = newTenantAdmin("prov")
        val (oltCode, odp, customer) = scaffold(token)
        val apiKey = newCollector(token)
        val serial = "SN-${uniq().uppercase()}"

        // Serial liar → tidak diterima sebagai metrik, tapi ditangkap ke kotak masuk.
        val first = postAsCollector(apiKey, batch(reading(serial, oltCode, -23.0)))
        assertThat(JsonPath.read<Int>(first, "$.accepted")).isZero()
        assertThat(JsonPath.read<List<String>>(first, "$.unknownSerialNumbers")).containsExactly(serial)

        val listed = inbox(token)
        assertThat(JsonPath.read<List<String>>(listed, "$[*].serialNumber")).containsExactly(serial)
        assertThat(JsonPath.read<String>(listed, "$[0].state")).isEqualTo("DISCOVERED")
        assertThat(JsonPath.read<Int>(listed, "$[0].seenCount")).isEqualTo(1)
        // Kode OLT teresolusi ke id inventory karena OLT-nya terdaftar.
        assertThat(JsonPath.read<String>(listed, "$[0].oltCode")).isEqualTo(oltCode)
        assertThat(JsonPath.read<String?>(listed, "$[0].oltId")).isNotNull()
        assertThat(JsonPath.read<Double>(listed, "$[0].lastRxPowerDbm")).isEqualTo(-23.0)
        val discoveredId = JsonPath.read<String>(listed, "$[0].id")

        // Terlihat lagi di siklus berikut → baris yang sama diperbarui, bukan digandakan.
        postAsCollector(apiKey, batch(reading(serial, oltCode, -24.0)))
        val reListed = inbox(token)
        assertThat(JsonPath.read<List<String>>(reListed, "$[*].id")).containsExactly(discoveredId)
        assertThat(JsonPath.read<Int>(reListed, "$[0].seenCount")).isEqualTo(2)

        // Operator menuntaskan: pilih pelanggan + port ODP.
        val provisioned = post(
            "/api/monitoring/discovered-onus/$discoveredId/provision", token,
            """{"customerId":"$customer","odpId":"$odp","portNumber":1}""",
            expected = 200,
        )
        assertThat(JsonPath.read<String>(provisioned, "$.state")).isEqualTo("PROVISIONED")

        // ONU kini terdaftar dan terpasang di port ODP yang dipilih.
        val onus = getJson("/api/customers/$customer/onus", token)
        assertThat(JsonPath.read<List<String>>(onus, "$[*].serialNumber")).containsExactly(serial)
        assertThat(JsonPath.read<List<String>>(onus, "$[*].odpId")).containsExactly(odp)
        assertThat(JsonPath.read<List<Int>>(onus, "$[*].odpPortNumber")).containsExactly(1)

        // Hilang dari kotak masuk yang menunggu.
        assertThat(JsonPath.read<List<String>>(inbox(token), "$[*].id")).isEmpty()

        // Serial kini dikenal → bacaan berikutnya diterima sebagai metrik biasa.
        val known = postAsCollector(apiKey, batch(reading(serial, oltCode, -22.0)))
        assertThat(JsonPath.read<Int>(known, "$.accepted")).isEqualTo(1)
        assertThat(JsonPath.read<List<String>>(known, "$.unknownSerialNumbers")).isEmpty()
    }

    @Test
    fun `saran auto-link menebak pelanggan menunggu instalasi, ODP, dan port`() {
        val token = newTenantAdmin("suggest")
        val (oltCode, odp, customer) = scaffold(token)
        val apiKey = newCollector(token)
        val serial = "SN-${uniq().uppercase()}"

        postAsCollector(apiKey, batch(reading(serial, oltCode, -22.0)))
        val listed = inbox(token)

        // Satu-satunya pelanggan menunggu instalasi + satu ODP kandidat → cocok tunggal.
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.confidence")).isEqualTo("HIGH")
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.customerId")).isEqualTo(customer)
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.odpId")).isEqualTo(odp)
        assertThat(JsonPath.read<Int>(listed, "$[0].suggestion.portNumber")).isEqualTo(1)

        // Operator 1-klik: menuntaskan memakai persis nilai yang disarankan.
        val discoveredId = JsonPath.read<String>(listed, "$[0].id")
        val suggestedCustomer = JsonPath.read<String>(listed, "$[0].suggestion.customerId")
        val suggestedOdp = JsonPath.read<String>(listed, "$[0].suggestion.odpId")
        val suggestedPort = JsonPath.read<Int>(listed, "$[0].suggestion.portNumber")
        val provisioned = post(
            "/api/monitoring/discovered-onus/$discoveredId/provision", token,
            """{"customerId":"$suggestedCustomer","odpId":"$suggestedOdp","portNumber":$suggestedPort}""",
            expected = 200,
        )
        assertThat(JsonPath.read<String>(provisioned, "$.state")).isEqualTo("PROVISIONED")

        val onus = getJson("/api/customers/$customer/onus", token)
        assertThat(JsonPath.read<List<String>>(onus, "$[*].odpId")).containsExactly(odp)
        assertThat(JsonPath.read<List<Int>>(onus, "$[*].odpPortNumber")).containsExactly(1)
    }

    @Test
    fun `WO PSB terbuka mengunci tebakan ke pelanggan order itu, mengalahkan yang terdekat`() {
        val token = newTenantAdmin("psbsug")
        val (oltCode, odp, awaitingFar) = scaffold(token)
        val apiKey = newCollector(token)
        val s = uniq().uppercase()

        // Pelanggan kedua tepat di lokasi ODP → paling dekat secara geografis.
        val awaitingNear = id(
            post(
                "/api/customers", token,
                """{"code":"C2-$s","name":"Pelanggan Dekat $s","address":"Jl. Uji 2",
                    "location":{"longitude":106.995,"latitude":-6.245}}""",
            ),
        )

        // Order pasang terbuka justru untuk pelanggan yang lebih jauh.
        post("/api/work-orders", token, """{"type":"PSB","title":"Pasang baru $s","customerId":"$awaitingFar"}""")

        postAsCollector(apiKey, batch(reading("SN-${uniq().uppercase()}", oltCode, -22.0)))
        val listed = inbox(token)

        // Dua pelanggan menunggu + satu ODP: tanpa WO ini MEDIUM & menebak yang terdekat
        // ($awaitingNear). WO PSB terbuka mengunci ke pelanggan order itu dan menaikkan keyakinan.
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.confidence")).isEqualTo("HIGH")
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.customerId")).isEqualTo(awaitingFar)
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.customerId")).isNotEqualTo(awaitingNear)
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.odpId")).isEqualTo(odp)
        assertThat(JsonPath.read<Int>(listed, "$[0].suggestion.portNumber")).isEqualTo(1)
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.reason")).contains("WO PSB")
    }

    @Test
    fun `tanpa pelanggan menunggu instalasi, saran hanya ODP dan port kosong berikutnya`() {
        val token = newTenantAdmin("lowsug")
        val (oltCode, odp, customer) = scaffold(token)
        val apiKey = newCollector(token)

        // Pelanggan satu-satunya dipasangi ONU di port 1 → tak ada lagi yang menunggu instalasi.
        val existing = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-OLD-${uniq().uppercase()}"}"""))
        post("/api/customers/onus/$existing/attach", token, """{"odpId":"$odp","portNumber":1}""", expected = 200)

        postAsCollector(apiKey, batch(reading("SN-${uniq().uppercase()}", oltCode, -22.0)))
        val listed = inbox(token)

        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.confidence")).isEqualTo("LOW")
        assertThat(JsonPath.read<String?>(listed, "$[0].suggestion.customerId")).isNull()
        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.odpId")).isEqualTo(odp)
        // Port 1 terpakai → saran melompat ke port kosong berikutnya.
        assertThat(JsonPath.read<Int>(listed, "$[0].suggestion.portNumber")).isEqualTo(2)
    }

    @Test
    fun `PON port yang belum terpetakan tidak menghasilkan saran`() {
        val token = newTenantAdmin("nosug")
        val (oltCode, _, _) = scaffold(token)
        val apiKey = newCollector(token)

        // Label PON port yang tak dikenal OLT → tak ada ODP kandidat.
        postAsCollector(apiKey, batch(reading("SN-${uniq().uppercase()}", oltCode, -22.0, ponPortLabel = "9/9/9")))
        val listed = inbox(token)

        assertThat(JsonPath.read<String>(listed, "$[0].suggestion.confidence")).isEqualTo("NONE")
        assertThat(JsonPath.read<String?>(listed, "$[0].suggestion.odpId")).isNull()
        assertThat(JsonPath.read<String?>(listed, "$[0].suggestion.customerId")).isNull()
    }

    @Test
    fun `mengabaikan ONU terdeteksi mengeluarkannya dari kotak masuk`() {
        val token = newTenantAdmin("ign")
        val (oltCode, _, _) = scaffold(token)
        val apiKey = newCollector(token)
        val serial = "SN-${uniq().uppercase()}"

        postAsCollector(apiKey, batch(reading(serial, oltCode, -20.0)))
        val discoveredId = JsonPath.read<String>(inbox(token), "$[0].id")

        val ignored = post("/api/monitoring/discovered-onus/$discoveredId/ignore", token, "", expected = 200)
        assertThat(JsonPath.read<String>(ignored, "$.state")).isEqualTo("IGNORED")

        // Keluar dari daftar yang menunggu, tapi masih bisa dilihat lewat filter state.
        assertThat(JsonPath.read<List<String>>(inbox(token), "$[*].id")).isEmpty()
        assertThat(JsonPath.read<List<String>>(inbox(token, "IGNORED"), "$[*].id")).containsExactly(discoveredId)
    }

    @Test
    fun `serial yang didaftarkan di luar kotak masuk menuntaskan sendiri barisnya`() {
        val token = newTenantAdmin("selfheal")
        val (oltCode, odp, customer) = scaffold(token)
        val apiKey = newCollector(token)
        val serial = "SN-${uniq().uppercase()}"

        // Muncul di kotak masuk lebih dulu.
        postAsCollector(apiKey, batch(reading(serial, oltCode, -21.0)))
        val discoveredId = JsonPath.read<String>(inbox(token), "$[0].id")

        // Operator justru mendaftarkan & memasangnya lewat halaman pelanggan (di luar kotak masuk).
        val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odp","portNumber":2}""", expected = 200)

        // Bacaan berikut untuk serial yang kini dikenal → baris kotak masuk dituntaskan sendiri.
        postAsCollector(apiKey, batch(reading(serial, oltCode, -21.0)))
        assertThat(JsonPath.read<List<String>>(inbox(token), "$[*].id")).isEmpty()
        assertThat(JsonPath.read<List<String>>(inbox(token, "PROVISIONED"), "$[*].id")).contains(discoveredId)
    }

    @Test
    fun `ONU terdeteksi tenant lain tidak terlihat`() {
        val tokenA = newTenantAdmin("iso-a")
        val tokenB = newTenantAdmin("iso-b")
        val (oltCodeA, _, _) = scaffold(tokenA)
        val apiKeyA = newCollector(tokenA)

        postAsCollector(apiKeyA, batch(reading("SN-${uniq().uppercase()}", oltCodeA, -20.0)))

        assertThat(JsonPath.read<List<String>>(inbox(tokenA), "$[*].id")).hasSize(1)
        assertThat(JsonPath.read<List<String>>(inbox(tokenB), "$[*].id")).isEmpty()
    }
}
