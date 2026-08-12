package com.duluin.ftth

import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncScheduler
import com.duluin.ftth.cpe.domain.model.ConnectedHost
import com.duluin.ftth.cpe.domain.model.FirmwareFile
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

    private fun seedAcsDevice(
        serial: String,
        genieacsId: String,
        lastInform: Instant = Instant.now(),
        ssid: String? = null,
        manufacturer: String = "ZTE",
    ) {
        acs.seedDevice(
            AcsDevice(
                genieacsId = genieacsId,
                serialNumber = serial,
                oui = "00AABB",
                productClass = "F670L",
                manufacturer = manufacturer,
                model = "F670L",
                softwareVersion = "V1.0.10",
                ipAddress = "100.64.0.5",
                lastInformAt = lastInform,
                ssid = ssid,
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
    fun `daftar firmware hanya yang cocok model, lalu upgrade terkirim dan tercatat`() {
        val token = newTenantAdmin("cpe-fw")
        val (genieacsId, deviceId) = syncedDevice(token)
        acs.seedFirmware(
            listOf(
                FirmwareFile("F670L-V2.bin", "V2.0.0", "F670L", "00AABB", FirmwareFile.FIRMWARE_FILE_TYPE, 12_000_000),
                FirmwareFile("Lain.bin", "V9", "XYZ999", null, FirmwareFile.FIRMWARE_FILE_TYPE, 5_000_000),
            ),
        )

        // Perangkat ber-productClass F670L → hanya firmware yang cocok yang muncul.
        val list = getJson("/api/cpe/devices/$deviceId/firmware", token)
        assertThat(JsonPath.read<List<String>>(list, "$[*].name")).containsExactly("F670L-V2.bin")
        assertThat(JsonPath.read<String>(list, "$[0].version")).isEqualTo("V2.0.0")

        val result = post(
            "/api/cpe/devices/$deviceId/firmware", token,
            """{"fileName":"F670L-V2.bin"}""", expected = 200,
        )
        assertThat(JsonPath.read<String>(result, "$.status")).isEqualTo("SUCCESS")
        assertThat(JsonPath.read<String>(result, "$.action")).isEqualTo("FIRMWARE_UPGRADE")
        assertThat(acs.firmwarePushes).containsExactly(genieacsId to "F670L-V2.bin")

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].action")).contains("FIRMWARE_UPGRADE")
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].status")).contains("SUCCESS")
    }

    @Test
    fun `upgrade ke firmware tak tersedia ditolak sebelum menyentuh ACS`() {
        val token = newTenantAdmin("cpe-fw-x")
        val (_, deviceId) = syncedDevice(token)
        acs.seedFirmware(
            listOf(FirmwareFile("F670L-V2.bin", "V2.0.0", "F670L", "00AABB", FirmwareFile.FIRMWARE_FILE_TYPE, 12_000_000)),
        )

        post("/api/cpe/devices/$deviceId/firmware", token, """{"fileName":"ngawur.bin"}""", expected = 400)
        assertThat(acs.firmwarePushes).isEmpty()
    }

    @Test
    fun `izin lihat CPE saja tak boleh kelola firmware`() {
        val slug = "cpe-fw-perm${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "CPE FW Co", admin, "Admin", pass))
        val adminToken = login(slug, admin)
        val (_, deviceId) = syncedDevice(adminToken)

        val permsJson = getJson("/api/permissions", adminToken)
        val viewPermId = JsonPath.read<List<String>>(permsJson, "$[?(@.code=='cpe.device.view')].id").first()
        val roleId = id(
            post("/api/roles", adminToken, """{"name":"Lihat CPE FW","permissionIds":["$viewPermId"]}""", expected = 201),
        )
        val limitedEmail = "fwviewer@$slug.test"
        post("/api/users", adminToken, """{"email":"$limitedEmail","name":"Viewer","password":"$pass","roleIds":["$roleId"]}""")
        val limitedToken = login(slug, limitedEmail)

        mockMvc.perform(
            get("/api/cpe/devices/$deviceId/firmware").header("Authorization", "Bearer $limitedToken"),
        ).andExpect(status().isForbidden)
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
    fun `factory reset terkirim ke ACS dan tercatat di jejak audit`() {
        val token = newTenantAdmin("cpe-fr")
        val (genieacsId, deviceId) = syncedDevice(token)

        val result = post("/api/cpe/devices/$deviceId/factory-reset", token, "", expected = 200)
        assertThat(JsonPath.read<String>(result, "$.status")).isEqualTo("SUCCESS")
        assertThat(JsonPath.read<String>(result, "$.action")).isEqualTo("FACTORY_RESET")
        assertThat(acs.factoryResetCalls).containsExactly(genieacsId)

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].action")).contains("FACTORY_RESET")
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].status")).contains("SUCCESS")
    }

    @Test
    fun `refresh ACS lapor terhubung saat perangkat terjangkau`() {
        val token = newTenantAdmin("cpe-refresh")
        val (genieacsId, deviceId) = syncedDevice(token)

        val result = post("/api/cpe/devices/$deviceId/refresh", token, "", expected = 200)
        assertThat(JsonPath.read<Boolean>(result, "$.connected")).isTrue()
        assertThat(acs.connectionRequests).containsExactly(genieacsId)

        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].action")).contains("REFRESH_ACS")
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[*].status")).contains("SUCCESS")
    }

    @Test
    fun `refresh ACS lapor tak terhubung saat perangkat tak terjangkau`() {
        val token = newTenantAdmin("cpe-refresh-off")
        val (_, deviceId) = syncedDevice(token)

        // Perangkat offline: NBI menerima permintaan tapi tak berhasil menjangkau perangkat.
        acs.connectionReachable = false
        val result = post("/api/cpe/devices/$deviceId/refresh", token, "", expected = 200)
        assertThat(JsonPath.read<Boolean>(result, "$.connected")).isFalse()

        // Not-Connect BUKAN kegagalan aksi — jejaknya tetap SUCCESS (perintah terkirim).
        val detail = getJson("/api/cpe/devices/$deviceId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.recentActions[?(@.action=='REFRESH_ACS')].status")).contains("SUCCESS")
    }

    @Test
    fun `izin lihat CPE saja tak boleh kelola perangkat`() {
        val slug = "cpe-mng-perm${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "CPE Mng Co", admin, "Admin", pass))
        val adminToken = login(slug, admin)
        val (_, deviceId) = syncedDevice(adminToken)

        val permsJson = getJson("/api/permissions", adminToken)
        val viewPermId = JsonPath.read<List<String>>(permsJson, "$[?(@.code=='cpe.device.view')].id").first()
        val roleId = id(
            post("/api/roles", adminToken, """{"name":"Lihat CPE Mng","permissionIds":["$viewPermId"]}""", expected = 201),
        )
        val limitedEmail = "mngviewer@$slug.test"
        post("/api/users", adminToken, """{"email":"$limitedEmail","name":"Viewer","password":"$pass","roleIds":["$roleId"]}""")
        val limitedToken = login(slug, limitedEmail)

        mockMvc.perform(
            post("/api/cpe/devices/$deviceId/factory-reset").header("Authorization", "Bearer $limitedToken"),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/api/cpe/devices/$deviceId/refresh").header("Authorization", "Bearer $limitedToken"),
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

    // =====================================================================================
    // Konsol ACS se-armada (halaman /acs)
    // =====================================================================================

    /** Membuat collector dan mengembalikan API key mentahnya (untuk menyuntik metrik optik). */
    private fun newCollector(token: String): String =
        JsonPath.read(
            post("/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}"""),
            "$.apiKey",
        )

    /**
     * Menyuntik satu bacaan optik lewat gerbang collector — jalur produksi yang sama
     * dengan agen SNMP. Metrik dicocokkan ke ONU lewat serial, jadi ONU-nya tak perlu
     * dipasang ke ODP dan `oltCode` di sini murni keterangan.
     */
    private fun ingestOptical(apiKey: String, serial: String, rx: Double, tx: Double? = null) {
        val now = Instant.now()
        val body = """
            {"batchId":"${uniq()}","collectedAt":"$now","readings":[
              {"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"ONLINE",
               "rxPowerDbm":$rx,"txPowerDbm":${tx ?: "null"},"uptimeSeconds":null,
               "distanceMeters":null,"observedAt":"$now"}]}
        """.trimIndent()
        mockMvc.perform(
            post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk)
    }

    /** Langganan aktif + akun PPPoE untuk [customer]; kembalikan username-nya. */
    private fun givePppoe(token: String, customer: String): String {
        val s = uniq()
        val nas = id(
            post(
                "/api/bng/nas", token,
                """{"name":"BRAS-$s","vendor":"MIKROTIK","address":"10.0.0.1",
                    "nasIdentifier":"bras-$s","coaSecret":null,"collectorId":null}""",
            ),
        )
        val plan = id(
            post(
                "/api/catalog/plans", token,
                """{"name":"Home $s","description":null,"price":150000,"downMbps":20,
                    "upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val sub = id(post("/api/customers/$customer/subscriptions", token, """{"planId":"$plan"}"""))
        post("/api/customers/subscriptions/$sub/activate", token, "", expected = 200)
        val username = "pppoe$s"
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123",
                "planId":"$plan","nasId":"$nas"}""",
        )
        return username
    }

    /** Role berisi tepat [codes], seorang pengguna memakainya, kembalikan tokennya. */
    private fun tokenWithPermissions(adminToken: String, slug: String, label: String, vararg codes: String): String {
        val permsJson = getJson("/api/permissions", adminToken)
        val ids = codes.joinToString(",") { code ->
            "\"" + JsonPath.read<List<String>>(permsJson, "$[?(@.code=='$code')].id").first() + "\""
        }
        val roleId = id(post("/api/roles", adminToken, """{"name":"$label ${uniq()}","permissionIds":[$ids]}"""))
        val email = "${label.lowercase(java.util.Locale.ROOT).replace(' ', '-')}${uniq()}@$slug.test"
        post("/api/users", adminToken, """{"email":"$email","name":"$label","password":"$pass","roleIds":["$roleId"]}""")
        return login(slug, email)
    }

    /** Tenant baru; kembalikan (slug, tokenAdmin) karena uji izin butuh slug untuk login. */
    private fun newTenant(prefix: String): Pair<String, String> {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return slug to login(slug, admin)
    }

    @Test
    fun `stats ACS menghitung online, offline, dan rata-rata sinyal`() {
        val token = newTenantAdmin("acs-stats")
        val apiKey = newCollector(token)
        val (_, serialA) = customerWithOnu(token)
        val (_, serialB) = customerWithOnu(token)
        val (_, serialLama) = customerWithOnu(token)
        seedAcsDevice(serialA, "genie-${uniq()}")
        seedAcsDevice(serialB, "genie-${uniq()}")
        // Inform sejam lalu → di luar ambang basi 15 menit → offline di mata konsol.
        seedAcsDevice(serialLama, "genie-${uniq()}", lastInform = Instant.now().minusSeconds(3600))
        scheduler.syncAll()
        ingestOptical(apiKey, serialA, rx = -20.0)
        ingestOptical(apiKey, serialB, rx = -24.0)

        val stats = getJson("/api/cpe/acs/stats", token)
        assertThat(JsonPath.read<Int>(stats, "$.totalDevices")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(stats, "$.onlineDevices")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(stats, "$.offlineDevices")).isEqualTo(1)
        assertThat(JsonPath.read<Double>(stats, "$.avgRxPowerDbm")).isEqualTo(-22.0)
        // Penyebutnya ikut dikembalikan: dua bacaan dari tiga perangkat, bukan kesehatan armada.
        assertThat(JsonPath.read<Int>(stats, "$.signalSampleCount")).isEqualTo(2)
    }

    @Test
    fun `rata-rata sinyal null saat belum ada bacaan optik`() {
        val token = newTenantAdmin("acs-nosig")
        val (_, serial) = customerWithOnu(token)
        seedAcsDevice(serial, "genie-${uniq()}")
        scheduler.syncAll()

        val stats = getJson("/api/cpe/acs/stats", token)
        assertThat(JsonPath.read<Int>(stats, "$.totalDevices")).isEqualTo(1)
        assertThat(JsonPath.read<Any?>(stats, "$.avgRxPowerDbm")).isNull()
        assertThat(JsonPath.read<Int>(stats, "$.signalSampleCount")).isZero()
    }

    @Test
    fun `daftar device ACS terisolasi antar tenant`() {
        val tokenA = newTenantAdmin("acs-isoA")
        val tokenB = newTenantAdmin("acs-isoB")
        val (_, serialA) = customerWithOnu(tokenA)
        val (_, serialB) = customerWithOnu(tokenB)
        seedAcsDevice(serialA, "genie-${uniq()}")
        seedAcsDevice(serialB, "genie-${uniq()}")
        scheduler.syncAll()

        // Satu GenieACS dipakai bersama semua tenant; RLS-lah satu-satunya yang memisahkan
        // armada mereka. Kalau tes ini jatuh, tenant A sedang melihat perangkat tenant B.
        val listA = getJson("/api/cpe/acs/devices", tokenA)
        assertThat(JsonPath.read<List<String>>(listA, "$[*].serialNumber")).containsExactly(serialA)
        val listB = getJson("/api/cpe/acs/devices", tokenB)
        assertThat(JsonPath.read<List<String>>(listB, "$[*].serialNumber")).containsExactly(serialB)
    }

    @Test
    fun `baris device memuat SSID, PPPoE, dan RX dari sumbernya masing-masing`() {
        val token = newTenantAdmin("acs-row")
        val apiKey = newCollector(token)
        val (customer, serial) = customerWithOnu(token)
        seedAcsDevice(serial, "genie-${uniq()}", ssid = "WiFi-Uji-5G")
        scheduler.syncAll()
        val username = givePppoe(token, customer)
        ingestOptical(apiKey, serial, rx = -21.5, tx = 2.5)

        val row = getJson("/api/cpe/acs/devices", token)
        // Tiga module berbeda bertemu di satu baris: SSID dari sync ACS, PPPoE dari bng,
        // RX/TX dari metrik optik OLT.
        assertThat(JsonPath.read<String>(row, "$[0].ssid")).isEqualTo("WiFi-Uji-5G")
        assertThat(JsonPath.read<String>(row, "$[0].pppoeUsername")).isEqualTo(username)
        assertThat(JsonPath.read<Double>(row, "$[0].rxPowerDbm")).isEqualTo(-21.5)
        assertThat(JsonPath.read<Double>(row, "$[0].txPowerDbm")).isEqualTo(2.5)
        assertThat(JsonPath.read<Boolean>(row, "$[0].online")).isTrue()
    }

    @Test
    fun `filter status dan pencarian menyaring daftar device`() {
        val token = newTenantAdmin("acs-filter")
        val (_, segar) = customerWithOnu(token)
        val (_, basi) = customerWithOnu(token)
        seedAcsDevice(segar, "genie-${uniq()}", ssid = "Rumah-Andi")
        seedAcsDevice(basi, "genie-${uniq()}", lastInform = Instant.now().minusSeconds(3600))
        scheduler.syncAll()

        val online = getJson("/api/cpe/acs/devices?status=ONLINE", token)
        assertThat(JsonPath.read<List<String>>(online, "$[*].serialNumber")).containsExactly(segar)
        val offline = getJson("/api/cpe/acs/devices?status=OFFLINE", token)
        assertThat(JsonPath.read<List<String>>(offline, "$[*].serialNumber")).containsExactly(basi)

        // Pencarian menyentuh serial maupun SSID, abai besar-kecil huruf.
        val bySerial = getJson("/api/cpe/acs/devices?q=${basi.lowercase(java.util.Locale.ROOT)}", token)
        assertThat(JsonPath.read<List<String>>(bySerial, "$[*].serialNumber")).containsExactly(basi)
        val bySsid = getJson("/api/cpe/acs/devices?q=rumah-andi", token)
        assertThat(JsonPath.read<List<String>>(bySsid, "$[*].serialNumber")).containsExactly(segar)
        assertThat(JsonPath.read<List<String>>(getJson("/api/cpe/acs/devices?q=tak-ada", token), "$[*].id")).isEmpty()
    }

    @Test
    fun `ekspor CSV membalas text-csv tanpa satu pun kredensial`() {
        val token = newTenantAdmin("acs-csv")
        val s = uniq().uppercase()
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Budi, S.Kom","address":"Jl. Uji",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val serial = "SN-$s"
        post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}""")
        seedAcsDevice(serial, "genie-${uniq()}", ssid = "WiFi-Budi")
        scheduler.syncAll()

        val response = mockMvc.perform(
            get("/api/cpe/acs/devices.csv").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response
        val csv = response.contentAsString

        assertThat(response.contentType).startsWith("text/csv")
        assertThat(response.getHeader("Content-Disposition")).contains("perangkat-acs.csv")
        assertThat(csv).startsWith("serial_number,customer_name,")
        assertThat(csv).contains("\r\n")
        // Nama berkoma wajib dikutip, kalau tidak kolomnya bergeser di spreadsheet.
        assertThat(csv).contains("\"Budi, S.Kom\"")
        assertThat(csv).contains(serial).contains("WiFi-Budi").contains("ONLINE")
        // Berkas ini beredar lewat email dan grup WhatsApp — tak boleh ada kata sandi
        // ACS/connection-request di dalamnya (nilainya dari application-test.yml).
        assertThat(csv).doesNotContain("rahasia-acs-uji").doesNotContain("rahasia-cr-uji")
        assertThat(csv).doesNotContain("password").doesNotContain("secret")
    }

    @Test
    fun `izin cpe-acs-view hanya membuka info server dan health, bukan armada`() {
        val (slug, adminToken) = newTenant("acs-tek")
        val (_, serial) = customerWithOnu(adminToken)
        seedAcsDevice(serial, "genie-${uniq()}")
        scheduler.syncAll()
        // Persis bekal seorang Teknisi: menyetel ONT di rumah pelanggan, tak melihat armada.
        val teknisi = tokenWithPermissions(adminToken, slug, "Teknisi ACS", "cpe.acs.view")

        val info = getJson("/api/cpe/acs/server", teknisi)
        assertThat(JsonPath.read<String>(info, "$.cwmpUrl")).isEqualTo("http://acs.uji.local:7547")
        assertThat(JsonPath.read<String>(info, "$.acsUsername")).isEqualTo("onu-fs")
        assertThat(JsonPath.read<String>(info, "$.acsPassword")).isEqualTo("rahasia-acs-uji")
        assertThat(JsonPath.read<String>(info, "$.connectionRequestUsername")).isEqualTo("incognito")
        assertThat(JsonPath.read<Boolean>(info, "$.configured")).isTrue()
        // Bawaan pabrik ONT 3600 itulah yang salah; nilai inilah yang harus diketik.
        assertThat(JsonPath.read<Int>(info, "$.periodicInformIntervalSeconds")).isEqualTo(300)
        assertThat(JsonPath.read<String>(getJson("/api/cpe/acs/health", teknisi), "$.status")).isEqualTo("ONLINE")

        for (path in listOf("/api/cpe/acs/devices", "/api/cpe/acs/stats", "/api/cpe/acs/logs", "/api/cpe/acs/devices.csv")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer $teknisi"))
                .andExpect(status().isForbidden)
        }
        mockMvc.perform(post("/api/cpe/acs/refresh-all").header("Authorization", "Bearer $teknisi"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `izin lihat perangkat saja tak membuka info server ACS`() {
        val (slug, adminToken) = newTenant("acs-dev-only")
        // Kebalikan uji sebelumnya: membuktikan kedua izin benar-benar berdiri sendiri.
        val operator = tokenWithPermissions(adminToken, slug, "Operator Armada", "cpe.device.view")

        mockMvc.perform(get("/api/cpe/acs/devices").header("Authorization", "Bearer $operator"))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/cpe/acs/server").header("Authorization", "Bearer $operator"))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/cpe/acs/health").header("Authorization", "Bearer $operator"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `health melaporkan OFFLINE tanpa membocorkan alamat NBI`() {
        val token = newTenantAdmin("acs-health")
        acs.failing = true

        val health = getJson("/api/cpe/acs/health", token)
        assertThat(JsonPath.read<String>(health, "$.status")).isEqualTo("OFFLINE")
        val message = JsonPath.read<String>(health, "$.message")
        assertThat(message).startsWith("Server ACS tak terjangkau")
        // Pesan exception RestClient menyisipkan URI penuh; ia harus berhenti di log server,
        // tak pernah sampai ke browser operator tenant.
        assertThat(message).doesNotContain("genieacs").doesNotContain("7557").doesNotContain("http")
    }

    @Test
    fun `segarkan batch hanya menyentuh perangkat online dan mencatat jejak audit`() {
        val token = newTenantAdmin("acs-bulk")
        val (_, serialA) = customerWithOnu(token)
        val (_, serialB) = customerWithOnu(token)
        val (_, serialLama) = customerWithOnu(token)
        val genieA = "genie-${uniq()}"
        val genieB = "genie-${uniq()}"
        seedAcsDevice(serialA, genieA)
        seedAcsDevice(serialB, genieB)
        // Perangkat basi dijamin menjawab "Not Connect"; memanggilnya hanya membakar anggaran.
        seedAcsDevice(serialLama, "genie-${uniq()}", lastInform = Instant.now().minusSeconds(3600))
        scheduler.syncAll()

        val result = post("/api/cpe/acs/refresh-all", token, "", expected = 200)
        assertThat(JsonPath.read<Int>(result, "$.candidates")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(result, "$.attempted")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(result, "$.connected")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(result, "$.skipped")).isZero()
        assertThat(acs.connectionRequests).containsExactlyInAnyOrder(genieA, genieB)

        val logs = getJson("/api/cpe/acs/logs", token)
        assertThat(JsonPath.read<List<String>>(logs, "$[?(@.action=='REFRESH_ACS')].serialNumber"))
            .containsExactlyInAnyOrder(serialA, serialB)
        assertThat(JsonPath.read<List<String>>(logs, "$[*].status")).containsOnly("SUCCESS")
    }

    @Test
    fun `log aktivitas ACS mengembalikan aksi terbaru lintas device, terbaru dulu`() {
        val token = newTenantAdmin("acs-logs")
        val (_, deviceSatu) = syncedDevice(token)
        val (_, deviceDua) = syncedDevice(token)

        post("/api/cpe/devices/$deviceSatu/refresh", token, "", expected = 200)
        post("/api/cpe/devices/$deviceDua/factory-reset", token, "", expected = 200)

        val logs = getJson("/api/cpe/acs/logs", token)
        assertThat(JsonPath.read<List<String>>(logs, "$[*].action"))
            .containsExactly("FACTORY_RESET", "REFRESH_ACS")
        assertThat(JsonPath.read<String>(logs, "$[0].deviceId")).isEqualTo(deviceDua)
        assertThat(JsonPath.read<String>(logs, "$[0].requestedByEmail")).isNotBlank()

        // Penyaringan per-device tetap ada untuk jendela detail.
        val satu = getJson("/api/cpe/acs/logs?deviceId=$deviceSatu", token)
        assertThat(JsonPath.read<List<String>>(satu, "$[*].action")).containsExactly("REFRESH_ACS")
    }

    @Test
    fun `log aktivitas ACS terisolasi antar tenant`() {
        val tokenA = newTenantAdmin("acs-logA")
        val tokenB = newTenantAdmin("acs-logB")
        val (_, deviceA) = syncedDevice(tokenA)
        val (_, deviceB) = syncedDevice(tokenB)
        post("/api/cpe/devices/$deviceA/refresh", tokenA, "", expected = 200)
        post("/api/cpe/devices/$deviceB/factory-reset", tokenB, "", expected = 200)

        assertThat(JsonPath.read<List<String>>(getJson("/api/cpe/acs/logs", tokenA), "$[*].deviceId"))
            .containsExactly(deviceA)
        assertThat(JsonPath.read<List<String>>(getJson("/api/cpe/acs/logs", tokenB), "$[*].deviceId"))
            .containsExactly(deviceB)
    }
}
