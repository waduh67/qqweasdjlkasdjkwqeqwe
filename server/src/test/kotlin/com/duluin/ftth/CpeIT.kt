package com.duluin.ftth

import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncScheduler
import com.duluin.ftth.cpe.domain.model.ConnectedHost
import com.duluin.ftth.cpe.domain.model.WifiNetwork
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Uji module CPE: sinkronisasi menautkan device GenieACS ke pelanggan lewat serial
 * ONU, lalu operator memantau (WiFi & host live) dan mengendalikan (reboot, ubah
 * WiFi) perangkat — tiap aksi menulis jejak audit.
 *
 * ACS diperankan [InMemoryAcsGateway] (profil test), jadi tak perlu GenieACS asli.
 * Sinkronisasi dipicu langsung lewat [CpeSyncScheduler.syncAll] — jalur produksi
 * yang sama, berjalan lintas tenant di luar konteks request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CpeIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var acs: InMemoryAcsGateway
    @Autowired private lateinit var scheduler: CpeSyncScheduler

    private val pass = "secret12345"
    private val wifiRef = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1"

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

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    /** Pelanggan + satu ONU terdaftar; kembalikan (customerId, serial). */
    private fun customerWithOnu(token: String): Pair<String, String> {
        val s = uniq().uppercase()
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val serial = "SN-$s"
        post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}""")
        return customer to serial
    }

    private fun seedAcsDevice(serial: String, genieacsId: String, lastInform: Instant = Instant.now()) {
        acs.seedDevice(
            AcsDevice(
                genieacsId = genieacsId,
                serialNumber = serial,
                oui = "00AABB",
                productClass = "F670L",
                manufacturer = "ZTE",
                model = "F670L",
                softwareVersion = "V1.0.10",
                ipAddress = "100.64.0.5",
                lastInformAt = lastInform,
            ),
        )
    }

    /** Pelanggan + ONU + device ACS tersinkron untuk [token]; kembalikan (genieacsId, deviceId). */
    private fun syncedDevice(token: String): Pair<String, String> {
        val (customer, serial) = customerWithOnu(token)
        val genieacsId = "genie-${uniq()}"
        seedAcsDevice(serial, genieacsId)
        scheduler.syncAll()
        val deviceId = JsonPath.read<String>(getJson("/api/cpe/devices?customerId=$customer", token), "$[0].id")
        return genieacsId to deviceId
    }

    @Test
    fun `sinkronisasi menautkan device ACS ke pelanggan lewat serial ONU`() {
        val token = newTenantAdmin("cpe")
        val (customer, serial) = customerWithOnu(token)
        val genieacsId = "genie-${uniq()}"
        seedAcsDevice(serial, genieacsId)

        scheduler.syncAll()

        val devices = getJson("/api/cpe/devices?customerId=$customer", token)
        assertThat(JsonPath.read<List<String>>(devices, "$[*].serialNumber")).containsExactly(serial)
        assertThat(JsonPath.read<String>(devices, "$[0].genieacsId")).isEqualTo(genieacsId)
        assertThat(JsonPath.read<String>(devices, "$[0].model")).isEqualTo("F670L")
        assertThat(JsonPath.read<String>(devices, "$[0].softwareVersion")).isEqualTo("V1.0.10")
        assertThat(JsonPath.read<Boolean>(devices, "$[0].online")).isTrue()
    }

    @Test
    fun `inform basi menandai perangkat offline`() {
        val token = newTenantAdmin("cpe-stale")
        val (customer, serial) = customerWithOnu(token)
        seedAcsDevice(serial, "genie-${uniq()}", lastInform = Instant.now().minusSeconds(3600))

        scheduler.syncAll()

        val devices = getJson("/api/cpe/devices?customerId=$customer", token)
        assertThat(JsonPath.read<Boolean>(devices, "$[0].online")).isFalse()
    }

    @Test
    fun `reboot berhasil dikirim ke ACS dan tercatat di jejak audit`() {
        val token = newTenantAdmin("cpe-reboot")
        val (customer, serial) = customerWithOnu(token)
        val genieacsId = "genie-${uniq()}"
        seedAcsDevice(serial, genieacsId)
        scheduler.syncAll()
        val deviceId = JsonPath.read<String>(getJson("/api/cpe/devices?customerId=$customer", token), "$[0].id")

        val result = post("/api/cpe/devices/$deviceId/reboot", token, "", expected = 200)
        assertThat(JsonPath.read<String>(result, "$.status")).isEqualTo("SUCCESS")
        assertThat(JsonPath.read<String>(result, "$.action")).isEqualTo("REBOOT")
        assertThat(acs.rebootCalls).containsExactly(genieacsId)

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].action")).contains("REBOOT")
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].status")).contains("SUCCESS")
        // "Siapa" terekam dari pengguna login.
        assertThat(JsonPath.read<String>(detail, "$.recentActions[0].requestedByEmail")).contains("@")
    }

    @Test
    fun `kegagalan ACS dicatat sebagai FAILED, bukan menggagalkan permintaan`() {
        val token = newTenantAdmin("cpe-fail")
        val (customer, serial) = customerWithOnu(token)
        seedAcsDevice(serial, "genie-${uniq()}")
        scheduler.syncAll()
        val deviceId = JsonPath.read<String>(getJson("/api/cpe/devices?customerId=$customer", token), "$[0].id")

        acs.failing = true
        val result = post("/api/cpe/devices/$deviceId/reboot", token, "", expected = 200)
        assertThat(JsonPath.read<String>(result, "$.status")).isEqualTo("FAILED")

        // Justru kegagalan yang paling perlu jejaknya — harus tetap tersimpan.
        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].status")).contains("FAILED")
    }

    @Test
    fun `WiFi dan host live dibaca dari ACS lalu SSID diubah`() {
        val token = newTenantAdmin("cpe-wifi")
        val (customer, serial) = customerWithOnu(token)
        val genieacsId = "genie-${uniq()}"
        seedAcsDevice(serial, genieacsId)
        acs.seedWifi(
            genieacsId,
            listOf(WifiNetwork(wifiRef, ssid = "RumahLama", passphrase = "sandilama", band = "2.4GHz", enabled = true)),
        )
        acs.seedHosts(
            genieacsId,
            listOf(ConnectedHost(hostName = "Laptop", ipAddress = "192.168.1.10", macAddress = "AA:BB:CC:DD:EE:FF", active = true)),
        )
        scheduler.syncAll()
        val deviceId = JsonPath.read<String>(getJson("/api/cpe/devices?customerId=$customer", token), "$[0].id")

        val live = getJson("/api/cpe/devices/$deviceId/live", token)
        assertThat(JsonPath.read<String>(live, "$.wifi[0].ssid")).isEqualTo("RumahLama")
        assertThat(JsonPath.read<String>(live, "$.hosts[0].hostName")).isEqualTo("Laptop")

        val result = post(
            "/api/cpe/devices/$deviceId/wifi", token,
            """{"ref":"$wifiRef","ssid":"RumahBaru","passphrase":"sandibaru123"}""",
            expected = 200,
        )
        assertThat(JsonPath.read<String>(result, "$.status")).isEqualTo("SUCCESS")
        assertThat(acs.wifiChanges.map { it.second.ssid }).contains("RumahBaru")

        val liveAfter = getJson("/api/cpe/devices/$deviceId/live", token)
        assertThat(JsonPath.read<String>(liveAfter, "$.wifi[0].ssid")).isEqualTo("RumahBaru")
    }

    @Test
    fun `ubah WiFi tanpa perubahan ditolak sebelum menyentuh ACS`() {
        val token = newTenantAdmin("cpe-noop")
        val (customer, serial) = customerWithOnu(token)
        seedAcsDevice(serial, "genie-${uniq()}")
        scheduler.syncAll()
        val deviceId = JsonPath.read<String>(getJson("/api/cpe/devices?customerId=$customer", token), "$[0].id")

        post("/api/cpe/devices/$deviceId/wifi", token, """{"ref":"$wifiRef"}""", expected = 400)
        assertThat(acs.wifiChanges).isEmpty()
    }

    @Test
    fun `device tenant lain tidak terlihat`() {
        val tokenA = newTenantAdmin("cpe-iso-a")
        val tokenB = newTenantAdmin("cpe-iso-b")
        val (customerA, serialA) = customerWithOnu(tokenA)
        seedAcsDevice(serialA, "genie-${uniq()}")

        scheduler.syncAll()

        // A melihat device-nya; B menanyakan customerId milik A tetap kosong (RLS).
        assertThat(JsonPath.read<List<String>>(getJson("/api/cpe/devices?customerId=$customerA", tokenA), "$[*].id")).hasSize(1)
        assertThat(JsonPath.read<List<String>>(getJson("/api/cpe/devices?customerId=$customerA", tokenB), "$[*].id")).isEmpty()
    }

    @Test
    fun `ping diagnostik berhasil, metriknya kembali dan tercatat di jejak`() {
        val token = newTenantAdmin("cpe-ping")
        val (genieacsId, deviceId) = syncedDevice(token)

        val result = post("/api/cpe/devices/$deviceId/diagnostics/ping", token, """{"host":"1.1.1.1"}""", expected = 200)
        assertThat(JsonPath.read<Boolean>(result, "$.ok")).isTrue()
        assertThat(JsonPath.read<String>(result, "$.state")).isEqualTo("Complete")
        assertThat(JsonPath.read<String>(result, "$.host")).isEqualTo("1.1.1.1")
        assertThat(JsonPath.read<Int>(result, "$.averageResponseMs")).isEqualTo(12)
        assertThat(acs.pingCalls.map { it.first }).containsExactly(genieacsId)
        assertThat(acs.pingCalls.first().second).isEqualTo("1.1.1.1")

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].action")).contains("PING_TEST")
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].status")).contains("SUCCESS")
    }

    @Test
    fun `ping tanpa host memakai sasaran bawaan`() {
        val token = newTenantAdmin("cpe-ping-def")
        val (_, deviceId) = syncedDevice(token)

        val result = post("/api/cpe/devices/$deviceId/diagnostics/ping", token, "{}", expected = 200)
        assertThat(JsonPath.read<Boolean>(result, "$.ok")).isTrue()
        // Host bawaan konfigurasi (8.8.8.8) dipakai saat pemanggil tak mengisi apa pun.
        assertThat(acs.pingCalls.first().second).isEqualTo("8.8.8.8")
    }

    @Test
    fun `uji kecepatan unduh mengembalikan throughput`() {
        val token = newTenantAdmin("cpe-speed")
        val (genieacsId, deviceId) = syncedDevice(token)

        val result = post("/api/cpe/devices/$deviceId/diagnostics/speedtest?direction=DOWNLOAD", token, "", expected = 200)
        assertThat(JsonPath.read<Boolean>(result, "$.ok")).isTrue()
        assertThat(JsonPath.read<String>(result, "$.direction")).isEqualTo("DOWNLOAD")
        assertThat(JsonPath.read<Double>(result, "$.throughputMbps")).isGreaterThan(0.0)
        assertThat(acs.speedTestCalls.map { it.first }).containsExactly(genieacsId)

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].action")).contains("SPEED_TEST")
    }

    @Test
    fun `diagnostik gagal saat ACS menolak, tercatat FAILED tanpa menggagalkan permintaan`() {
        val token = newTenantAdmin("cpe-diag-fail")
        val (_, deviceId) = syncedDevice(token)

        acs.failing = true
        val result = post("/api/cpe/devices/$deviceId/diagnostics/ping", token, "{}", expected = 200)
        assertThat(JsonPath.read<Boolean>(result, "$.ok")).isFalse()

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[?(@.action=='PING_TEST')].status")).contains("FAILED")
    }

    @Test
    fun `izin lihat CPE saja tak boleh menjalankan diagnostik`() {
        val slug = "cpe-diag-perm${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "CPE Diag Co", admin, "Admin", pass))
        val adminToken = login(slug, admin)
        val (_, deviceId) = syncedDevice(adminToken)

        // Role dengan HANYA cpe.device.view: boleh lihat, tak boleh diagnostik.
        val permsJson = getJson("/api/permissions", adminToken)
        val viewPermId = JsonPath.read<List<String>>(permsJson, "$[?(@.code=='cpe.device.view')].id").first()
        val roleId = id(
            post("/api/roles", adminToken, """{"name":"Lihat CPE","permissionIds":["$viewPermId"]}""", expected = 201),
        )
        val limitedEmail = "viewer@$slug.test"
        post("/api/users", adminToken, """{"email":"$limitedEmail","name":"Viewer","password":"$pass","roleIds":["$roleId"]}""")
        val limitedToken = login(slug, limitedEmail)

        mockMvc.perform(
            post("/api/cpe/devices/$deviceId/diagnostics/ping").header("Authorization", "Bearer $limitedToken")
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `tanpa izin cpe ditolak 403`() {
        val slug = "cpe-perm${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "CPE Perm Co", admin, "Admin", pass))
        val adminToken = login(slug, admin)

        val permsJson = getJson("/api/permissions", adminToken)
        val viewPermId = JsonPath.read<List<String>>(permsJson, "$[?(@.code=='iam.user.view')].id").first()
        val roleId = id(
            post("/api/roles", adminToken, """{"name":"Tanpa CPE","permissionIds":["$viewPermId"]}""", expected = 201),
        )
        val limitedEmail = "nocpe@$slug.test"
        post("/api/users", adminToken, """{"email":"$limitedEmail","name":"No CPE","password":"$pass","roleIds":["$roleId"]}""")
        val limitedToken = login(slug, limitedEmail)

        mockMvc.perform(
            get("/api/cpe/devices?customerId=${UUID.randomUUID()}").header("Authorization", "Bearer $limitedToken"),
        ).andExpect(status().isForbidden)
    }
}
