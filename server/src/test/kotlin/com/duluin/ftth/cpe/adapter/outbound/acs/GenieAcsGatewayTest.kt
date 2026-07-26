package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.FirmwareFile
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant

/**
 * Menguji pemetaan pohon parameter TR-069 mentah GenieACS ke tipe bersih adapter,
 * serta bentuk request task (reboot / setParameterValues) — bagian yang paling
 * ruwet dan mudah salah. NBI dipalsukan lewat [MockRestServiceServer]; tak ada ACS,
 * tak ada context Spring.
 */
class GenieAcsGatewayTest {

    private fun fixture(): Pair<GenieAcsGateway, MockRestServiceServer> {
        val builder = RestClient.builder().baseUrl("http://acs.test:7557")
        val server = MockRestServiceServer.bindTo(builder).build()
        val gateway = GenieAcsGateway(
            restClient = builder.build(),
            downloadUrl = "http://speed.test/10MB.zip",
            uploadUrl = "http://speed.test/upload",
            uploadBytes = 10_485_760,
            // Tenggat & interval kecil agar polling di test selesai seketika.
            diagnosticsTimeout = Duration.ofMillis(500),
            pollInterval = Duration.ofMillis(5),
        )
        return gateway to server
    }

    @Test
    fun `listDevices memetakan pohon TR-098 ke AcsDevice`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/devices/")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[$TR098_DEVICE]", MediaType.APPLICATION_JSON))

        val devices = gateway.listDevices()

        assertThat(devices).hasSize(1)
        val d = devices.single()
        assertThat(d.genieacsId).isEqualTo("ACS-001")
        assertThat(d.serialNumber).isEqualTo("ZTEGD1234567")
        assertThat(d.oui).isEqualTo("00AABB")
        assertThat(d.productClass).isEqualTo("F670L")
        assertThat(d.manufacturer).isEqualTo("ZTE")
        assertThat(d.model).isEqualTo("F670L")
        assertThat(d.softwareVersion).isEqualTo("V1.0.10")
        assertThat(d.ipAddress).isEqualTo("100.64.0.5")
        assertThat(d.lastInformAt).isEqualTo(Instant.parse("2026-07-25T10:00:00Z"))
        server.verify()
    }

    @Test
    fun `listDevices mengabaikan device tanpa serial`() {
        val (gateway, server) = fixture()
        val noSerial = """{"_id":"ACS-XX","_deviceId":{"_OUI":"00AABB"}}"""
        server.expect(requestTo(containsString("/devices/")))
            .andRespond(withSuccess("[$noSerial]", MediaType.APPLICATION_JSON))

        assertThat(gateway.listDevices()).isEmpty()
        server.verify()
    }

    @Test
    fun `wifiNetworks membaca WLANConfiguration`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/devices/")))
            .andRespond(withSuccess("[$TR098_WIFI]", MediaType.APPLICATION_JSON))

        val wifi = gateway.wifiNetworks("ACS-001")

        assertThat(wifi).hasSize(1)
        val w = wifi.single()
        assertThat(w.ref).isEqualTo("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1")
        assertThat(w.ssid).isEqualTo("RumahLama")
        assertThat(w.passphrase).isEqualTo("sandilama")
        assertThat(w.band).isEqualTo("n")
        assertThat(w.enabled).isTrue()
        server.verify()
    }

    @Test
    fun `connectedHosts membaca tabel Hosts`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/devices/")))
            .andRespond(withSuccess("[$TR098_HOSTS]", MediaType.APPLICATION_JSON))

        val hosts = gateway.connectedHosts("ACS-001")

        assertThat(hosts).hasSize(1)
        val h = hosts.single()
        assertThat(h.hostName).isEqualTo("Laptop")
        assertThat(h.ipAddress).isEqualTo("192.168.1.10")
        assertThat(h.macAddress).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(h.active).isTrue()
        server.verify()
    }

    @Test
    fun `reboot mengirim task reboot dengan connection_request`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/devices/ACS-001/tasks")))
            .andExpect(requestTo(containsString("connection_request")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.name").value("reboot"))
            .andRespond(withSuccess())

        gateway.reboot("ACS-001")
        server.verify()
    }

    @Test
    fun `applyWifi mengirim setParameterValues untuk SSID dan passphrase`() {
        val (gateway, server) = fixture()
        val ref = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1"
        server.expect(requestTo(containsString("/devices/ACS-001/tasks")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.name").value("setParameterValues"))
            .andExpect(jsonPath("$.parameterValues[0][0]").value("$ref.SSID"))
            .andExpect(jsonPath("$.parameterValues[0][1]").value("RumahBaru"))
            .andExpect(jsonPath("$.parameterValues[1][0]").value("$ref.KeyPassphrase"))
            .andExpect(jsonPath("$.parameterValues[1][1]").value("sandibaru123"))
            .andRespond(withSuccess())

        gateway.applyWifi("ACS-001", WifiChange(ref, ssid = "RumahBaru", passphrase = "sandibaru123"))
        server.verify()
    }

    @Test
    fun `runPing menyetel input lalu memetakan hasil IPPingDiagnostics`() {
        val (gateway, server) = fixture()
        // 1) Deteksi akar model data (currentRoot).
        server.expect(requestTo(containsString("/devices/")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[$TR098_ROOT]", MediaType.APPLICATION_JSON))
        // 2) Task setParameterValues memicu diagnostik.
        server.expect(requestTo(containsString("/devices/ACS-001/tasks")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.name").value("setParameterValues"))
            .andExpect(jsonPath("$.parameterValues[0][0]").value("InternetGatewayDevice.IPPingDiagnostics.DiagnosticsState"))
            .andExpect(jsonPath("$.parameterValues[0][1]").value("Requested"))
            .andExpect(jsonPath("$.parameterValues[1][0]").value("InternetGatewayDevice.IPPingDiagnostics.Host"))
            .andExpect(jsonPath("$.parameterValues[1][1]").value("1.1.1.1"))
            .andExpect(jsonPath("$.parameterValues[2][1]").value("4"))
            .andRespond(withSuccess())
        // 3) Poll: perangkat sudah menuntaskan.
        server.expect(requestTo(containsString("/devices/")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[$TR098_PING_DONE]", MediaType.APPLICATION_JSON))

        val ping = gateway.runPing("ACS-001", host = "1.1.1.1", count = 4)

        assertThat(ping.complete).isTrue()
        assertThat(ping.host).isEqualTo("1.1.1.1")
        assertThat(ping.successCount).isEqualTo(4)
        assertThat(ping.failureCount).isEqualTo(0)
        assertThat(ping.averageResponseMs).isEqualTo(12)
        server.verify()
    }

    @Test
    fun `runSpeedTest unduh menghitung throughput dari byte dan durasi`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/devices/")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[$TR098_ROOT]", MediaType.APPLICATION_JSON))
        server.expect(requestTo(containsString("/devices/ACS-001/tasks")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.name").value("setParameterValues"))
            .andExpect(jsonPath("$.parameterValues[0][0]").value("InternetGatewayDevice.DownloadDiagnostics.DiagnosticsState"))
            .andExpect(jsonPath("$.parameterValues[1][0]").value("InternetGatewayDevice.DownloadDiagnostics.DownloadURL"))
            .andExpect(jsonPath("$.parameterValues[1][1]").value("http://speed.test/10MB.zip"))
            .andRespond(withSuccess())
        server.expect(requestTo(containsString("/devices/")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[$TR098_DOWNLOAD_DONE]", MediaType.APPLICATION_JSON))

        val speed = gateway.runSpeedTest("ACS-001", SpeedDirection.DOWNLOAD)

        assertThat(speed.complete).isTrue()
        assertThat(speed.direction).isEqualTo(SpeedDirection.DOWNLOAD)
        assertThat(speed.testBytes).isEqualTo(10_485_760)
        assertThat(speed.durationMs).isEqualTo(900)
        // 10.485.760 byte × 8 / 1e6 / 0,9 s ≈ 93,2 Mbps.
        assertThat(speed.throughputMbps).isCloseTo(93.2, within(0.5))
        server.verify()
    }

    @Test
    fun `availableFirmware menyaring ke image firmware yang cocok model`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/files/")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[$FIRMWARE_FILE, $CONFIG_FILE, $OTHER_FIRMWARE]", MediaType.APPLICATION_JSON))

        val files = gateway.availableFirmware(productClass = "F670L", oui = "00AABB")

        // Berkas config (bukan firmware) & firmware model lain tersaring.
        assertThat(files).hasSize(1)
        val f = files.single()
        assertThat(f.name).isEqualTo("F670L-V2.bin")
        assertThat(f.version).isEqualTo("V2.0.0")
        assertThat(f.productClass).isEqualTo("F670L")
        assertThat(f.sizeBytes).isEqualTo(12_000_000)
        server.verify()
    }

    @Test
    fun `pushFirmware mengirim task download dengan fileName dan fileType`() {
        val (gateway, server) = fixture()
        server.expect(requestTo(containsString("/devices/ACS-001/tasks")))
            .andExpect(requestTo(containsString("connection_request")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.name").value("download"))
            .andExpect(jsonPath("$.fileName").value("F670L-V2.bin"))
            .andExpect(jsonPath("$.fileType").value("1 Firmware Upgrade Image"))
            .andRespond(withSuccess())

        gateway.pushFirmware(
            "ACS-001",
            FirmwareFile("F670L-V2.bin", "V2.0.0", "F670L", "00AABB", FirmwareFile.FIRMWARE_FILE_TYPE, 12_000_000),
        )
        server.verify()
    }

    companion object {
        private val TR098_DEVICE = """
            {
              "_id": "ACS-001",
              "_lastInform": "2026-07-25T10:00:00.000Z",
              "_deviceId": {
                "_Manufacturer": "ZTE", "_OUI": "00AABB",
                "_ProductClass": "F670L", "_SerialNumber": "ZTEGD1234567"
              },
              "InternetGatewayDevice": {
                "DeviceInfo": {
                  "ModelName": {"_value": "F670L", "_type": "xsd:string"},
                  "SoftwareVersion": {"_value": "V1.0.10", "_type": "xsd:string"}
                },
                "WANDevice": {"1": {"WANConnectionDevice": {"1": {"WANIPConnection": {"1": {
                  "ExternalIPAddress": {"_value": "100.64.0.5"}
                }}}}}}
              }
            }
        """.trimIndent()

        private val TR098_WIFI = """
            {
              "_id": "ACS-001",
              "InternetGatewayDevice": {"LANDevice": {"1": {"WLANConfiguration": {"1": {
                "SSID": {"_value": "RumahLama"},
                "KeyPassphrase": {"_value": "sandilama"},
                "Standard": {"_value": "n"},
                "Enable": {"_value": true}
              }}}}}
            }
        """.trimIndent()

        private val TR098_HOSTS = """
            {
              "_id": "ACS-001",
              "InternetGatewayDevice": {"LANDevice": {"1": {"Hosts": {"Host": {"1": {
                "HostName": {"_value": "Laptop"},
                "IPAddress": {"_value": "192.168.1.10"},
                "MACAddress": {"_value": "AA:BB:CC:DD:EE:FF"},
                "Active": {"_value": true}
              }}}}}}
            }
        """.trimIndent()

        /** Cukup untuk deteksi akar (currentRoot) tanpa detail lain. */
        private val TR098_ROOT = """
            {"_id": "ACS-001", "InternetGatewayDevice": {"DeviceInfo": {}}}
        """.trimIndent()

        private val TR098_PING_DONE = """
            {
              "_id": "ACS-001",
              "InternetGatewayDevice": {"IPPingDiagnostics": {
                "DiagnosticsState": {"_value": "Complete"},
                "SuccessCount": {"_value": 4},
                "FailureCount": {"_value": 0},
                "AverageResponseTime": {"_value": 12},
                "MinimumResponseTime": {"_value": 9},
                "MaximumResponseTime": {"_value": 18}
              }}
            }
        """.trimIndent()

        private val TR098_DOWNLOAD_DONE = """
            {
              "_id": "ACS-001",
              "InternetGatewayDevice": {"DownloadDiagnostics": {
                "DiagnosticsState": {"_value": "Complete"},
                "BOMTime": {"_value": "2026-07-25T10:00:00.000Z"},
                "EOMTime": {"_value": "2026-07-25T10:00:00.900Z"},
                "TestBytesReceived": {"_value": 10485760}
              }}
            }
        """.trimIndent()

        // Dokumen fs.files GenieACS: _id = nama berkas, metadata polos (bukan pohon TR-069).
        private val FIRMWARE_FILE = """
            {"_id": "F670L-V2.bin", "length": 12000000,
             "metadata": {"fileType": "1 Firmware Upgrade Image", "version": "V2.0.0",
                          "productClass": "F670L", "oui": "00AABB"}}
        """.trimIndent()

        private val CONFIG_FILE = """
            {"_id": "config.xml", "length": 4096,
             "metadata": {"fileType": "3 Vendor Configuration File", "productClass": "F670L"}}
        """.trimIndent()

        private val OTHER_FIRMWARE = """
            {"_id": "OtherModel.bin", "length": 8000000,
             "metadata": {"fileType": "1 Firmware Upgrade Image", "version": "V1.0", "productClass": "XYZ999"}}
        """.trimIndent()
    }
}
