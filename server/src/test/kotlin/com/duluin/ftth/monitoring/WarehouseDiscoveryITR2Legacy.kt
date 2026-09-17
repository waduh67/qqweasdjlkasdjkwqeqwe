package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.service.CpeSyncService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
class WarehouseDiscoveryITR2Legacy : WarehouseDiscoveryFixture() {
    @Autowired private lateinit var sync: CpeSyncService
    @MockitoSpyBean private lateinit var acs: AcsGateway

    @ParameterizedTest
    @ValueSource(strings = ["KNOWN_OLD", "MISSING_OLD", "MISSING_ONLY", "BAD_INFORM", "BLANK_INFORM"])
    fun `CPE-R2-3 legacy distinguishes absent evidence from explicit pre-episode data`(mode: String) {
        val token = newTenantAdmin("r2legacy")
        val device = legacy(token)
        val now = Instant.now()
        val genie = "R2-${device.serial}"
        TenantContext.runAs(tenantId(token)) { sync.sync(listOf(AcsDevice(genie, device.serial, null, null, "Vendor",
            "Original", null, null, now, "original", observedFieldsAt = now))) }
        val id = scalar(token, "SELECT id FROM cpe_device")
        val old = if (mode in setOf("KNOWN_OLD", "MISSING_OLD")) ",\"_timestamp\":\"1970-01-01T00:00:00Z\"" else ""
        val sibling = if (mode == "KNOWN_OLD") ",\"_timestamp\":\"$now\"" else ""
        val inform = when (mode) { "BAD_INFORM" -> "bad"; "BLANK_INFORM" -> " "; else -> now.toString() }
        WarehouseReviewAcsServer().use { server ->
            server.document.set("""[{"_id":"$genie","_deviceId":{"_SerialNumber":"${device.serial}"},"_lastInform":"$inform",
                "InternetGatewayDevice":{"DeviceInfo":{"ModelName":{"_value":"old-private"$old}},
                "LANDevice":{"1":{"WLANConfiguration":{"1":{"SSID":{"_value":"legacy"$sibling},
                    "KeyPassphrase":{"_value":"old-secret"$old}}}}}}}]""")
            Mockito.doAnswer { server.gateway.findDevice(genie) }.`when`(acs).findDevice(genie)
            Mockito.doAnswer { server.gateway.wifiNetworks(genie) }.`when`(acs).wifiNetworks(genie)
            Mockito.doAnswer { server.gateway.connectedHosts(genie) }.`when`(acs).connectedHosts(genie)
            val response = mockMvc.perform(get("/api/cpe/devices/$id/live").header("Authorization", "Bearer $token")).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(if (mode == "MISSING_ONLY") 200 else 404)
            assertThat(scalar(token, "SELECT model FROM cpe_device WHERE id='$id'")).isEqualTo("Original")
        }
    }
}
