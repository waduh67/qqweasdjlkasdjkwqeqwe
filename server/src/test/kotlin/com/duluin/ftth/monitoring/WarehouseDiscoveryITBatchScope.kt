package com.duluin.ftth.monitoring

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITBatchScope : WarehouseDiscoveryFixture() {
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var retention: com.duluin.ftth.monitoring.application.service.IngestBatchRetention

    @Test
    fun `batch replay identity belongs to its authenticated tenant and collector`() {
        val first = newTenantAdmin("batchfirst")
        val second = newTenantAdmin("batchsecond")
        val firstKey = newCollector(first)
        val otherFirstKey = newCollector(first)
        val secondKey = newCollector(second)
        val id = "scoped-${uniq()}"
        val payload = batch(reading("UNKNOWN-SCOPED", "ONLINE", -20.0), batchId = id)
        for (key in listOf(firstKey, secondKey, otherFirstKey)) {
            val response = postAsCollector("/api/collector/metrics", key, payload)
            assertThat(JsonPath.read<Boolean>(response, "$.duplicate")).isFalse()
        }
        assertThat(JsonPath.read<Boolean>(postAsCollector("/api/collector/metrics", firstKey, payload), "$.duplicate")).isTrue()
        assertThat(scalar(first, "SELECT count(*) FROM ingest_batch WHERE batch_id='$id'")).isEqualTo("2")
        assertThat(scalar(second, "SELECT count(*) FROM ingest_batch WHERE batch_id='$id'")).isEqualTo("1")
        assertThat(scalar(second, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
        assertThat(scalar(first, "SELECT relforcerowsecurity FROM pg_class WHERE oid='ingest_batch'::regclass")).isIn("t", "true")
        val purged = com.duluin.ftth.common.tenant.TenantContext.runAs(tenantId(first)) {
            retention.purge(java.time.Instant.now().plusSeconds(1))
        }
        assertThat(purged).isEqualTo(2)
        assertThat(scalar(second, "SELECT count(*) FROM ingest_batch WHERE batch_id='$id'")).isEqualTo("1")
    }

    @Test
    fun `batch retention covers the complete age and future tolerance window`() {
        val now = java.time.Instant.parse("2026-09-16T12:00:00Z")
        assertThat(com.duluin.ftth.monitoring.application.service.IngestBatchRetention.cutoff(now))
            .isEqualTo(now.minusSeconds(72 * 3600 + 300))
    }
}
