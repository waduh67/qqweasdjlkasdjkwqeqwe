package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.service.CpeSyncService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITReviewLegacyBounds : WarehouseDiscoveryFixture() {
    @Autowired private lateinit var sync: CpeSyncService
    @MockitoSpyBean private lateinit var acs: AcsGateway

    @Test
    fun `legacy compatibility never permits known future parameter timestamps`() {
        val token = newTenantAdmin("legacybounds")
        val device = legacy(token)
        val now = Instant.now()
        val genie = "BOUNDS-${device.serial}"
        TenantContext.runAs(tenantId(token)) { sync.sync(listOf(AcsDevice(genie, device.serial, null, null, "Vendor",
            "Model", null, null, now, "original", observedFieldsAt = now))) }
        val id = scalar(token, "SELECT id FROM cpe_device")
        WarehouseReviewAcsServer().use { server ->
            server.document.set("""[{"_id":"$genie","_deviceId":{"_SerialNumber":"${device.serial}"},"_lastInform":"$now",
                "InternetGatewayDevice":{"DeviceInfo":{"ModelName":{"_value":"Model","_timestamp":"$now"}},
                "LANDevice":{"1":{"WLANConfiguration":{"1":{"SSID":{"_value":"legacy","_timestamp":"$now"},
                    "KeyPassphrase":{"_value":"future-secret","_timestamp":"${now.plusSeconds(86400)}"}}}}}}}]""")
            Mockito.doAnswer { server.gateway.findDevice(genie) }.`when`(acs).findDevice(genie)
            Mockito.doAnswer { server.gateway.wifiNetworks(genie) }.`when`(acs).wifiNetworks(genie)
            Mockito.doAnswer { server.gateway.connectedHosts(genie) }.`when`(acs).connectedHosts(genie)
            val response = mockMvc.perform(get("/api/cpe/devices/$id/live").header("Authorization", "Bearer $token")).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(404)
            assertThat(response.contentAsString).doesNotContain("future-secret")
        }
    }
}
