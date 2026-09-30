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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryBaselineIT : WarehouseDiscoveryFixture() {
    @Test
    fun `legacy active telemetry remains operational and batch replay is inert`() {
        val token = newTenantAdmin("discbaseline")
        val device = legacy(token)
        val apiKey = newCollector(token)
        val payload = batch(reading(device.serial, "LOS", -30.0))
        val result = postAsCollector("/api/collector/metrics", apiKey, payload)
        assertThat(JsonPath.read<Int>(result, "$.accepted")).isEqualTo(1)
        assertThat(scalar(token, "SELECT status FROM onu WHERE id='${device.onu}'")).isEqualTo("LOS")
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE onu_id='${device.onu}'")).isEqualTo("1")
        assertThat(scalar(token, "SELECT count(*) FROM alarm WHERE entity_id='${device.onu}' AND status<>'CLEARED'")).isEqualTo("1")
        val replay = postAsCollector("/api/collector/metrics", apiKey, payload)
        assertThat(JsonPath.read<Boolean>(replay, "$.duplicate")).isTrue()
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE onu_id='${device.onu}'")).isEqualTo("1")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
    }

    @Test
    fun `raw registration still requires warehouse authorization`() {
        val token = newTenantAdmin("discsource")
        val customer = customer(token)
        val response = mockMvc.perform(post("/api/customers/$customer/onus")
            .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .content("""{"serialNumber":"UNISSUED-${uniq()}"}"""))
            .andExpect(status().isConflict).andReturn().response.contentAsString
        assertThat(response).contains("USE_WORKORDER_ASSET_WORKFLOW")
        assertThat(scalar(token, "SELECT count(*) FROM onu")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
    }

    @Test
    fun `foreign observation remains discovery without creating stock`() {
        val foreign = legacy(newTenantAdmin("discforeign"))
        val token = newTenantAdmin("discisolation")
        val result = postAsCollector("/api/collector/metrics", newCollector(token), batch(reading(foreign.serial, "ONLINE", -20.0)))
        assertThat(JsonPath.read<Int>(result, "$.accepted")).isZero()
        val discovery = scalar(token, "SELECT id FROM discovered_onu WHERE serial_number='${foreign.serial}'")
        assertThat(scalar(token, "SELECT state FROM discovered_onu WHERE id='$discovery'")).isEqualTo("DISCOVERED")
        assertThat(scalar(token, "SELECT count(*) FROM onu")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("0")
    }
}
