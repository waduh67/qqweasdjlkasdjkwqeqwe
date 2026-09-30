package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITReviewFuture : CustomerAssetReplacementFixture() {
    @Test
    fun `T5 tolerated future sample cannot become immutable evidence outside a later real removal interval`() {
        val old = ownershipCase("LOAN")
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val stock = fixture(receipt.stock.token)
        val registration = request("POST", "/api/monitoring/collectors", receipt.stock.token, """{"name":"Future","pollIntervalSeconds":60}""")
        assertThat(registration.status).isEqualTo(201)
        val collector = UUID.fromString(mapper.readTree(registration.contentAsString).path("collector").path("id").asString())
        val future = Instant.now().plusSeconds(240)
        val sample = OnuReading(requireNotNull(receipt.input.lines.single().serial), "OLT-X", null, OnuOperationalStatus.LOS, -30.0, null, null, null, future)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingest(collector, tenant,
            MetricBatch(UUID.randomUUID().toString(), Instant.now(), listOf(sample))) }
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.installation.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)
        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,"workOrderId":"$order","evidenceId":"$evidence"}""", "future-remove")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM onu_metric m JOIN onu o ON o.id=m.onu_id WHERE m.time>=o.retired_at")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM monitoring_unassigned_observation WHERE reason='FUTURE_OBSERVATION'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM alarm")).isEqualTo("0")
        }
    }
}
