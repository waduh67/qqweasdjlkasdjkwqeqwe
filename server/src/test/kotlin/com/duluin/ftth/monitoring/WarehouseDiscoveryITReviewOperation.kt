package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.customer.LegacyOnuTestFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDiscoveryITReviewOperation : CustomerDeploymentFixture() {
    @MockitoSpyBean private lateinit var acs: AcsGateway

    private data class DeviceCase(val installation: Installation, val serial: String, val genie: String, val deviceId: String)
    private fun admitted(): DeviceCase {
        val serial = "OP-${UUID.randomUUID()}".uppercase()
        val installation = installation(serials = listOf(serial, "$serial-X"))
        assertThat(consume(installation).status).isEqualTo(201)
        val stock = fixture(installation.receipt.stock.token)
        val now = Instant.now()
        val genie = "ACS-$serial"
        TenantContext.runAs(stock.tenant) { context.getBean(CpeSyncService::class.java).sync(listOf(
            AcsDevice(genie, serial, null, null, "Vendor", "B-model", null, "192.0.2.2", now, "B-private", observedFieldsAt = now))) }
        return DeviceCase(installation, serial, genie, stock.transaction { scalar("SELECT id FROM cpe_device") })
    }

    @Test
    fun `CPE-3 real gateway mixed future fields are refused by the live HTTP service`() {
        val device = admitted()
        WarehouseReviewAcsServer().use { server ->
            val now = Instant.now()
            server.document.set("""[{"_id":"${device.genie}","_deviceId":{"_SerialNumber":"${device.serial}"},"_lastInform":"$now",
                "InternetGatewayDevice":{"DeviceInfo":{"ModelName":{"_value":"B-model","_timestamp":"$now"}},
                    "LANDevice":{"1":{"WLANConfiguration":{"1":{"SSID":{"_value":"B-private","_timestamp":"$now"},
                        "KeyPassphrase":{"_value":"future-secret","_timestamp":"${now.plusSeconds(86400)}"}}}}}}}]""")
            Mockito.doAnswer { server.gateway.findDevice(device.genie) }.`when`(acs).findDevice(device.genie)
            Mockito.doAnswer { server.gateway.wifiNetworks(device.genie) }.`when`(acs).wifiNetworks(device.genie)
            Mockito.doAnswer { server.gateway.connectedHosts(device.genie) }.`when`(acs).connectedHosts(device.genie)
            val response = request("GET", "/api/cpe/devices/${device.deviceId}/live", device.installation.receipt.stock.token)
            assertThat(response.status).isEqualTo(404)
            assertThat(response.contentAsString).doesNotContain("future-secret")
            assertThat(server.tasks.get()).isZero()
        }
    }

    @Test
    fun `CPE-2 queued real gateway diagnostic produces neither successful response nor history`() {
        val device = admitted()
        WarehouseReviewAcsServer().use { server ->
            server.document.set("""[{"_id":"${device.genie}","_deviceId":{"_SerialNumber":"${device.serial}"},
                "InternetGatewayDevice":{"IPPingDiagnostics":{"DiagnosticsState":{"_value":"Complete"},"SuccessCount":{"_value":99}}}}]""")
            Mockito.doAnswer { server.gateway.runPing(device.genie, "owned.test", 4) }.`when`(acs).runPing(device.genie, "owned.test", 4)
            val response = request("POST", "/api/cpe/devices/${device.deviceId}/diagnostics/ping", device.installation.receipt.stock.token,
                """{"host":"owned.test"}""")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            val body = mapper.readTree(response.contentAsString)
            assertThat(body.path("ok").asBoolean()).isFalse()
            assertThat(body.path("successCount").isNull).isTrue()
            fixture(device.installation.receipt.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM cpe_action_log WHERE device_id='${device.deviceId}' AND status='SUCCESS'")).isEqualTo("0")
            }
            assertThat(server.tasks.get()).isEqualTo(1)
        }
    }

    @Test
    fun `CPE-1 ownership commit is not held across ACS IO and stale final result is denied`() {
        val device = admitted()
        val other = tenant()
        val customer = request("POST", "/api/customers", other,
            """{"code":"OTHER","name":"Other","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}""")
        assertThat(customer.status).isEqualTo(201)
        val customerId = mapper.readTree(customer.contentAsString).path("id").asString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        WarehouseReviewAcsServer().use { server ->
            server.document.set("""[{"_id":"${device.genie}","_deviceId":{"_SerialNumber":"${device.serial}"},"InternetGatewayDevice":{}}]""")
            server.onTask.set { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
            Mockito.doAnswer { server.gateway.runPing(device.genie, "owned.test", 4) }.`when`(acs).runPing(device.genie, "owned.test", 4)
            Executors.newFixedThreadPool(2).use { pool ->
                val diagnostic = pool.submit<Int> { request("POST", "/api/cpe/devices/${device.deviceId}/diagnostics/ping",
                    device.installation.receipt.stock.token, """{"host":"owned.test"}""").status }
                check(entered.await(10, TimeUnit.SECONDS))
                val newOwner = pool.submit<String> { LegacyOnuTestFixture.stage(customerId, device.serial) }
                try {
                    newOwner.get(5, TimeUnit.SECONDS)
                } finally { release.countDown() }
                assertThat(diagnostic.get(30, TimeUnit.SECONDS)).isEqualTo(404)
                newOwner.get(30, TimeUnit.SECONDS)
            }
            val denied = request("POST", "/api/cpe/devices/${device.deviceId}/refresh", device.installation.receipt.stock.token)
            assertThat(denied.status).isEqualTo(404)
            assertThat(server.tasks.get()).isEqualTo(1)
        }
    }
}
