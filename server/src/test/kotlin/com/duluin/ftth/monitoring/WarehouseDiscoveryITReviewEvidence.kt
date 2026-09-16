package com.duluin.ftth.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.http.MediaType

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITReviewEvidence : WarehouseDiscoveryFixture() {
    @Test
    fun `T8 persisted accepted metric explains source and selected path`() {
        val token = newTenantAdmin("decision")
        val device = legacy(token)
        val key = newCollector(token)
        postAsCollector("/api/collector/metrics", key, batch(reading(device.serial, "ONLINE", -20.0)))
        assertThat(scalar(token, "SELECT coalesce(to_jsonb(m)->'attribution'->>'source','') FROM onu_metric m WHERE onu_id='${device.onu}'"))
            .isEqualTo("COLLECTOR")
        assertThat(scalar(token, "SELECT to_jsonb(m)->'attribution'->>'topologyRevision' FROM onu_metric m WHERE onu_id='${device.onu}'"))
            .isEqualTo("0")
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING_OBSERVED", "NULL_OBSERVED", "MISSING_COLLECTED", "NULL_COLLECTED"])
    fun `malformed missing or null required clocks fail HTTP shape validation without fabricated evidence`(kind: String) {
        val token = newTenantAdmin("missingclock")
        val key = newCollector(token)
        val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
        val body = mapper.readTree(batch(reading("UNKNOWN", "ONLINE", -20.0))) as tools.jackson.databind.node.ObjectNode
        val target = if (kind.endsWith("OBSERVED")) body.path("readings")[0] as tools.jackson.databind.node.ObjectNode else body
        val field = if (kind.endsWith("OBSERVED")) "observedAt" else "collectedAt"
        if (kind.startsWith("MISSING")) target.remove(field) else target.putNull(field)
        val response = mockMvc.perform(post("/api/collector/metrics").header("X-Collector-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andReturn().response
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(400)
        assertThat(scalar(token, "SELECT count(*) FROM ingest_batch")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation")).isEqualTo("0")
    }
}
