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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Uji broadcast pemberitahuan gangguan Phase 3: dari sebuah insiden berakar ODC,
 * operator menyiarkan pesan ke seluruh pelanggan terdampak, lalu riwayat & hasil
 * per penerima tersimpan. "Siapa yang terdampak" dihitung ulang oleh incident dari
 * akar masalah — pelanggan tanpa nomor telepon dilewati, bukan gagal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIT {

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

    /** Daftar pelanggan (opsional bertelepon/beralamat email) + ONU, pasang ke port ODP, kembalikan serial ONU. */
    private fun attachOnu(token: String, odpId: String, port: Int, phone: String?, email: String? = null): String {
        val s = uniq().uppercase()
        val phoneField = phone?.let { ""","phone":"$it"""" } ?: ""
        val emailField = email?.let { ""","email":"$it"""" } ?: ""
        val customer = id(
            post("/api/customers", token, """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji","location":{"longitude":106.99,"latitude":-6.24}$phoneField$emailField}"""),
        )
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

    private fun get(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    /**
     * Menyalakan gateway WA mode LOG untuk tenant. Gateway bawaan MATI (aman-secara-default),
     * jadi tanpa ini tiap broadcast — termasuk MANUAL — hanya dicatat SKIPPED "Gateway WA nonaktif".
     * Mode LOG "mengirim" ke log dan dihitung SENT, jadi cukup untuk menguji jalur kirim.
     */
    private fun enableWhatsAppGateway(token: String) = setChannels(token, gateway = true, email = false)

    /**
     * Menyetel kedua saklar kanal sekaligus. SMTP platform tak disetel di lingkungan uji, jadi
     * kanal email jatuh ke mode log dan dihitung SENT — cukup untuk menguji jalur pemilihan
     * kanal, alamat tujuan, dan pencatatan riwayatnya.
     */
    private fun setChannels(token: String, gateway: Boolean, email: Boolean) {
        mockMvc.perform(
            put("/api/notifications/settings").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"provider":"LOG","gatewayEnabled":$gateway,"emailEnabled":$email,"notifyOnSubscriptionLifecycle":false,"notifyOnInvoiceReminder":false,"notifyOnWorkOrderSchedule":false,"notifyOnIncidentOpen":false}""",
                ),
        ).andExpect(status().isOk)
    }

    @Test
    fun `broadcast menyasar seluruh pelanggan terdampak insiden dan mencatat hasil per penerima`() {
        val token = newTenantAdmin("notif")
        enableWhatsAppGateway(token) // tanpa gateway aktif, semua penerima jadi SKIPPED
        val chain = buildChain(token)
        // Dua pelanggan bertelepon + satu tanpa telepon, semuanya di bawah ODC yang sama.
        val a = attachOnu(token, chain.odp, port = 1, phone = "628110000001")
        val b = attachOnu(token, chain.odp, port = 2, phone = "628110000002")
        val c = attachOnu(token, chain.odp, port = 3, phone = null)
        val apiKey = newCollector(token)

        // Banjir LOS di bawah ODC → satu insiden berakar ODC.
        sendMetrics(apiKey, reading(a, "LOS", null), reading(b, "LOS", null), reading(c, "LOS", null))
        val incidentId = JsonPath.read<String>(get("/api/incidents", token), "$[0].id")

        // Siarkan pemberitahuan gangguan. Kanal dibiarkan default (WhatsApp).
        val created = post(
            "/api/notifications/broadcasts", token,
            """{"incidentId":"$incidentId","message":"Layanan Anda sedang terganggu, tim kami menanganinya."}""",
        )
        assertThat(JsonPath.read<String>(created, "$.channel")).isEqualTo("WHATSAPP")
        // Ketiga pelanggan di bawah ODC jadi sasaran; yang bertelepon terkirim, yang tidak dilewati.
        assertThat(JsonPath.read<Int>(created, "$.recipientCount")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(created, "$.sentCount")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(created, "$.skippedCount")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(created, "$.failedCount")).isEqualTo(0)
        val broadcastId = JsonPath.read<String>(created, "$.id")

        // Riwayat memuat broadcast tadi.
        val history = get("/api/notifications/broadcasts", token)
        assertThat(JsonPath.read<List<Any>>(history, "$.content[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(history, "$.content[0].id")).isEqualTo(broadcastId)

        // Detail merinci tiap penerima beserta status pengirimannya.
        val detail = get("/api/notifications/broadcasts/$broadcastId", token)
        assertThat(JsonPath.read<List<Any>>(detail, "$.recipients[*]")).hasSize(3)
        assertThat(JsonPath.read<List<String>>(detail, "$.recipients[*].status"))
            .containsExactlyInAnyOrder("SENT", "SENT", "SKIPPED")
    }

    @Test
    fun `broadcast lewat kanal email menyurati alamat pelanggan dan mencatatnya di riwayat`() {
        val token = newTenantAdmin("notifmail")
        setChannels(token, gateway = false, email = true)
        val chain = buildChain(token)
        // Satu pelanggan beralamat email, satu hanya bertelepon — di kanal email yang kedua dilewati.
        val a = attachOnu(token, chain.odp, port = 1, phone = "628110000003", email = "a@contoh.id")
        val b = attachOnu(token, chain.odp, port = 2, phone = "628110000004")
        val apiKey = newCollector(token)

        sendMetrics(apiKey, reading(a, "LOS", null), reading(b, "LOS", null))
        val incidentId = JsonPath.read<String>(get("/api/incidents", token), "$[0].id")

        val created = post(
            "/api/notifications/broadcasts", token,
            """{"incidentId":"$incidentId","message":"Layanan Anda sedang terganggu.","channel":"EMAIL"}""",
        )
        assertThat(JsonPath.read<String>(created, "$.channel")).isEqualTo("EMAIL")
        assertThat(JsonPath.read<Int>(created, "$.sentCount")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(created, "$.skippedCount")).isEqualTo(1)

        // Riwayat menyimpan alamat email sebagai tujuan, bukan nomor teleponnya.
        val detail = get("/api/notifications/broadcasts/${JsonPath.read<String>(created, "$.id")}", token)
        assertThat(JsonPath.read<List<String?>>(detail, "$.recipients[*].destination"))
            .contains("a@contoh.id")
            .doesNotContain("628110000004")
        assertThat(JsonPath.read<List<String>>(detail, "$.recipients[*].detail"))
            .contains("Alamat email kosong")
    }

    @Test
    fun `broadcast untuk insiden yang tidak ada ditolak 404`() {
        val token = newTenantAdmin("notif404")
        post(
            "/api/notifications/broadcasts", token,
            """{"incidentId":"${UUID.randomUUID()}","message":"halo"}""",
            expected = 404,
        )
    }
}
