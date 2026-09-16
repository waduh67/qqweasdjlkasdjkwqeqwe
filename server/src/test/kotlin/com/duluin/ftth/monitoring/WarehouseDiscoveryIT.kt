package com.duluin.ftth.monitoring

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryIT : WarehouseDiscoveryFixture() {
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var ingestion: com.duluin.ftth.monitoring.application.service.MetricIngestionService

    @Test
    fun `unissued discovered provision is a stable conflict and leaves observation actionable`() {
        val token = newTenantAdmin("discred")
        val customer = customer(token)
        val serial = "UNKNOWN-${uniq()}"
        postAsCollector("/api/collector/metrics", newCollector(token), batch(reading(serial, "ONLINE", -20.0)))
        val discovery = scalar(token, "SELECT id FROM discovered_onu")
        mockMvc.perform(post("/api/monitoring/discovered-onus/$discovery/provision")
            .header("Authorization", "Bearer $token").header("Idempotency-Key", "unissued")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"customerId":"$customer","odpId":null,"portNumber":null,"installRxPowerDbm":null}"""))
            .andExpect(status().isConflict)
        assertThat(scalar(token, "SELECT state FROM discovered_onu WHERE id='$discovery'")).isEqualTo("DISCOVERED")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("0")
    }

    @Test
    fun `expired and future collector samples never change legacy live state`() {
        val token = newTenantAdmin("disctime")
        val device = legacy(token)
        val apiKey = newCollector(token)
        for (instant in listOf(Instant.now().minusSeconds(72 * 3600 + 60), Instant.now().plusSeconds(360))) {
            val sample = """{"serialNumber":"${device.serial}","oltCode":"OLT-X","ponPortLabel":"1/1/1",
                "status":"LOS","rxPowerDbm":-30.0,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"$instant"}"""
            val result = postAsCollector("/api/collector/metrics", apiKey, batch(sample))
            assertThat(JsonPath.read<Int>(result, "$.accepted")).isZero()
        }
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE onu_id='${device.onu}'")).isEqualTo("0")
        assertThat(scalar(token, "SELECT status FROM onu WHERE id='${device.onu}'")).isEqualTo("PENDING")
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation WHERE reason='UNTRUSTED_TIMESTAMP'")).isEqualTo("2")
    }

    @Test
    fun `malformed timestamps remain durable unassigned observations and replay is inert`() {
        val token = newTenantAdmin("discmalformed")
        val device = legacy(token)
        val apiKey = newCollector(token)
        for (timestamp in listOf("not-a-time", "2026-09-16T10:00:00", "2026-09-16T10:00:00+25:00")) {
            val sample = """{"serialNumber":"${device.serial}","oltCode":"OLT-X","ponPortLabel":null,"status":"LOS",
                "rxPowerDbm":null,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"$timestamp"}"""
            val payload = batch(sample)
            val result = postAsCollector("/api/collector/metrics", apiKey, payload)
            assertThat(JsonPath.read<Int>(result, "$.accepted")).isZero()
            assertThat(JsonPath.read<Boolean>(postAsCollector("/api/collector/metrics", apiKey, payload), "$.duplicate")).isTrue()
        }
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation WHERE reason='MALFORMED_TIMESTAMP' AND observed_at IS NULL")).isEqualTo("3")
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric")).isEqualTo("0")
    }

    @Test
    fun `a foreign device path cannot update a known legacy installation`() {
        val token = newTenantAdmin("discpath")
        val (_, serial) = provisionAttachedOnu(token)
        val oltCode = scalar(token, "SELECT code FROM olt")
        val apiKey = newCollector(token)
        val correct = reading(serial, "ONLINE", -20.0).replace("OLT-X", oltCode)
        assertThat(JsonPath.read<Int>(postAsCollector("/api/collector/metrics", apiKey, batch(correct)), "$.accepted")).isEqualTo(1)
        val wrong = reading(serial, "LOS", -30.0).replace(oltCode, "OLT-FOREIGN")
        assertThat(JsonPath.read<Int>(postAsCollector("/api/collector/metrics", apiKey, batch(wrong)), "$.accepted")).isZero()
        assertThat(scalar(token, "SELECT status FROM onu WHERE serial_number='$serial'")).isEqualTo("ONLINE")
        assertThat(scalar(token, "SELECT count(*) FROM alarm")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation WHERE reason='PATH_MISMATCH'")).isEqualTo("1")
    }

    @Test
    fun `server polls use server time rather than a supplied device timestamp`() {
        val token = newTenantAdmin("discserverclock")
        val device = legacy(token)
        val before = Instant.now().minusSeconds(1)
        val tenant = tenantId(token)
        val result = com.duluin.ftth.common.tenant.TenantContext.runAs(tenant) {
            ingestion.ingestReadings(tenant, listOf(com.duluin.ftth.contract.OnuReading(device.serial, "OLT-X", null,
                com.duluin.ftth.contract.OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.EPOCH)))
        }
        assertThat(result.accepted).isEqualTo(1)
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE onu_id='${device.onu}' AND time>='$before'")).isEqualTo("1")
    }

    @Test
    fun `out of database range timestamp is retained without attempting a timestamp insert`() {
        val token = newTenantAdmin("discextremetime")
        val device = legacy(token)
        val sample = """{"serialNumber":"${device.serial}","oltCode":"OLT-X","ponPortLabel":null,"status":"LOS",
            "rxPowerDbm":null,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"${Instant.MAX}"}"""
        val result = postAsCollector("/api/collector/metrics", newCollector(token), batch(sample))
        assertThat(JsonPath.read<Int>(result, "$.accepted")).isZero()
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation WHERE observed_at IS NULL")).isEqualTo("1")
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric")).isEqualTo("0")
    }
}
