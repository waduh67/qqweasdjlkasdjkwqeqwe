package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import org.assertj.core.api.Assertions.assertThat
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
        return GenieAcsGateway(builder.build()) to server
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
    }
}
