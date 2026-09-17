package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.LegacyOnuTestFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITR2Preparation : WarehouseDiscoveryFixture() {
    @Autowired private lateinit var sync: CpeSyncService
    @MockitoSpyBean private lateinit var acs: AcsGateway

    @ParameterizedTest
    @ValueSource(strings = ["FIRMWARE", "PING"])
    fun `CPE-R2-2 committed owner loss during preparatory GET prevents every subsequent task POST`(mode: String) {
        val token = newTenantAdmin("r2prepare")
        val device = legacy(token)
        val now = Instant.now()
        val genie = "PREP-${device.serial}"
        TenantContext.runAs(tenantId(token)) { sync.sync(listOf(AcsDevice(genie, device.serial, null, null, "Vendor", "Model", null, null, now, "own", observedFieldsAt = now))) }
        val id = scalar(token, "SELECT id FROM cpe_device")
        val other = newTenantAdmin("r2other")
        val otherCustomer = customer(other)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        R2AcsServer().use { server ->
            server.document.set("""[{"_id":"$genie","_deviceId":{"_SerialNumber":"${device.serial}"},"_lastInform":"$now","InternetGatewayDevice":{}}]""")
            server.files.set("""[{"_id":"owned.bin","length":100,"metadata":{"fileType":"1 Firmware Upgrade Image"}}]""")
            server.onGet.set { path ->
                if ((mode == "FIRMWARE" && path.startsWith("/files")) || (mode == "PING" && path.startsWith("/devices"))) {
                    entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
                }
            }
            Mockito.doAnswer { server.gateway.availableFirmware(null, null) }.`when`(acs).availableFirmware(null, null)
            Mockito.doAnswer { invocation -> server.gateway.runPing(genie, "target.invalid", 4, invocation.getArgument(3)) }.`when`(acs)
                .runPing(Mockito.eq(genie) ?: genie, Mockito.eq("target.invalid") ?: "target.invalid", Mockito.eq(4), Mockito.any<() -> Unit>() ?: {})
            Mockito.doAnswer { invocation -> server.gateway.pushFirmware(genie, invocation.getArgument(1)); Unit }.`when`(acs)
                .pushFirmware(Mockito.eq(genie) ?: genie, Mockito.any(com.duluin.ftth.cpe.domain.model.FirmwareFile::class.java)
                    ?: com.duluin.ftth.cpe.domain.model.FirmwareFile("owned.bin", null, null, null, "1 Firmware Upgrade Image", 100))
            Executors.newSingleThreadExecutor().use { executor ->
                val response = executor.submit<Int> {
                    val suffix = if (mode == "FIRMWARE") "firmware" else "diagnostics/ping"
                    val body = if (mode == "FIRMWARE") """{"fileName":"owned.bin"}""" else """{"host":"target.invalid"}"""
                    mockMvc.perform(post("/api/cpe/devices/$id/$suffix").header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status
                }
                try { check(entered.await(10, TimeUnit.SECONDS)); LegacyOnuTestFixture.stage(otherCustomer, device.serial) }
                finally { release.countDown() }
                assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(404)
            }
            assertThat(server.posts.get()).isZero()
        }
    }
}
