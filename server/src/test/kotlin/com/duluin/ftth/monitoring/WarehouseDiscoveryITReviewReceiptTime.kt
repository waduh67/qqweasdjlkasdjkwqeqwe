package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.monitoring.application.service.IngestBatchRetention
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITReviewReceiptTime : CustomerDeploymentFixture() {
    @Test
    fun `T7 delayed transaction receipt survives retention while its sample is still acceptable`() {
        val token = tenant()
        val stock = fixture(token)
        val registration = request("POST", "/api/monitoring/collectors", token, """{"name":"Receipt clock","pollIntervalSeconds":60}""")
        assertThat(registration.status).isEqualTo(201)
        val collector = UUID.fromString(mapper.readTree(registration.contentAsString).path("collector").path("id").asString())
        val id = UUID.randomUUID().toString()
        lateinit var acceptedAt: Instant
        lateinit var batch: MetricBatch
        stock.transaction {
            sql("SELECT transaction_timestamp()")
            acceptedAt = Instant.now()
            batch = MetricBatch(id, acceptedAt, listOf(OnuReading("UNASSIGNED", "OLT-X", null,
                OnuOperationalStatus.ONLINE, null, null, null, null, acceptedAt.plusSeconds(299))))
            context.getBean(MetricIngestionService::class.java).ingest(collector, tenant, batch)
        }
        TenantContext.runAs(stock.tenant) { context.getBean(IngestBatchRetention::class.java).purge(acceptedAt) }
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM ingest_batch WHERE batch_id='$id'")).isEqualTo("1")
            assertThat(context.getBean(MetricIngestionService::class.java).ingest(collector, tenant, batch).duplicate).isTrue()
        }
    }
}
