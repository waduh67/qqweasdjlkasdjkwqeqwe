package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.inventory.ReceiptRealStorage
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(ReceiptRealStorage::class)
class WarehouseTemporalNumericIT : WarehouseNumericReuseFixture() {
    @Test fun `actual numeric ONU reuse keeps delayed A telemetry and stale ACS fields away from B`() {
        val original = numericCase()
        val usage = numericUse(original)
        val installedA = numericInstall(original)
        val signatureA = numericHandover(original, installedA)
        numericReturn(original, usage)
        numericComplete(original, signatureA)
        saveCommand("/api/work-orders/${original.workOrder}/approve", original.stock.token, "{}", "numeric-temporal-approve-A")
        val reviewPath = "/api/work-orders/${original.workOrder}/materials/workbench/approval-review"
        val reviewA = request("GET", reviewPath, original.stock.token)
        assertThat(reviewA.status).withFailMessage(reviewA.contentAsString).isEqualTo(200)
        val first = mapper.readTree(installedA.original)
        val asset = first.path("assetId").asString()
        val assignmentA = first.path("assignmentId").asString()
        val serial = original.issue.path("lines").single { it.path("dimension").path("stockIdentityId").asString() == asset }.path("serial").asString()
        val observedA = Instant.now()
        val stale = AcsDevice("NUMERIC-CPE-$asset", serial, null, null, "Vendor", "A-model", null, "192.0.2.10",
            observedA, "A-private", observedFieldsAt = observedA)
        val probe = fixture(original.stock.token)
        val sync = context.getBean(CpeSyncService::class.java)
        TenantContext.runAs(probe.tenant) { sync.sync(listOf(stale)) }
        val oldCpe = probe.transaction { scalar("SELECT id FROM cpe_device WHERE customer_id='${original.customer}'") }

        val replacement = numericReissue(original, installedA)
        val installedB = numericInstall(replacement, "numeric-reuse")
        val second = mapper.readTree(installedB.original)
        assertThat(second.path("assetId").asString()).isEqualTo(asset)
        val assignmentB = second.path("assignmentId").asString()
        assertThat(assignmentB).isNotEqualTo(assignmentA)
        val signatureB = numericHandover(replacement, installedB, "numeric-reuse")
        numericComplete(replacement, signatureB, "numeric-reuse")
        saveCommand("/api/work-orders/${replacement.workOrder}/approve", replacement.stock.token, "{}", "numeric-temporal-approve-B")
        assertThat(numericTotals(replacement)).isEqualTo("917500|82500|0|9|1|1|10|2")
        val before = numericAudit(replacement)
        val beforeStatus = probe.transaction { scalar("SELECT status FROM onu WHERE assignment_id='$assignmentB'") }
        val historical = request("GET", reviewPath, original.stock.token)
        assertThat(historical.status).withFailMessage(historical.contentAsString).isEqualTo(200)
        assertThat(historical.contentAsString).isEqualTo(reviewA.contentAsString)
        val registered = request("POST", "/api/monitoring/collectors", original.stock.token,
            """{"name":"Actual reuse collector","pollIntervalSeconds":60}""")
        assertThat(registered.status).withFailMessage(registered.contentAsString).isEqualTo(201)
        val collector = UUID.fromString(mapper.readTree(registered.contentAsString).path("collector").path("id").asString())
        val batch = MetricBatch(UUID.randomUUID().toString(), Instant.now(), listOf(
            OnuReading(serial, "OLT-X", "1/1/1", OnuOperationalStatus.LOS, -31.0, null, null, null, observedA)))
        probe.transaction {
            val ingestion = context.getBean(MetricIngestionService::class.java)
            assertThat(ingestion.ingest(collector, tenant, batch).accepted).isEqualTo(1)
            assertThat(ingestion.ingest(collector, tenant, batch).duplicate).isTrue()
            assertThat(scalar("SELECT count(*) FROM onu_metric metric JOIN onu episode ON episode.id=metric.onu_id WHERE episode.assignment_id='$assignmentA'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu_metric metric JOIN onu episode ON episode.id=metric.onu_id WHERE episode.assignment_id='$assignmentB'")).isEqualTo("0")
            assertThat(scalar("SELECT status FROM onu WHERE assignment_id='$assignmentB'")).isEqualTo(beforeStatus)
            assertThat(scalar("SELECT count(*) FROM alarm")).isEqualTo("0")
            val observations = context.getBean(CustomerObservationApi::class.java)
            assertThat(observations.resolveObservation(serial, observedA).episode?.onu?.customerId).isEqualTo(UUID.fromString(original.customer))
            assertThat(observations.currentEpisode(serial)?.onu?.customerId).isEqualTo(UUID.fromString(replacement.customer))
        }
        TenantContext.runAs(probe.tenant) { sync.sync(listOf(stale)) }
        probe.transaction {
            assertThat(context.getBean(CpeApi::class.java).findDevicesForCustomer(UUID.fromString(replacement.customer))).isEmpty()
            assertThat(scalar("SELECT customer_id||'|'||ssid FROM cpe_device WHERE id='$oldCpe'")).isEqualTo("${original.customer}|A-private")
        }
        val now = Instant.now()
        val fresh = stale.copy(lastInformAt = now, observedFieldsAt = now, model = "B-model", ssid = "B-private", ipAddress = "192.0.2.20")
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            listOf(stale, fresh).map { sample -> pool.submit {
                barrier.await(20, TimeUnit.SECONDS)
                TenantContext.runAs(probe.tenant) { sync.sync(listOf(sample)) }
            } }.forEach { it.get(30, TimeUnit.SECONDS) }
        }
        probe.transaction {
            val current = context.getBean(CpeApi::class.java).findDevicesForCustomer(UUID.fromString(replacement.customer)).single()
            assertThat(current.deviceId.toString()).isNotEqualTo(oldCpe)
            assertThat(current.model).isEqualTo("B-model")
            assertThat(current.ipAddress).isEqualTo("192.0.2.20")
            assertThat(scalar("SELECT count(*) FROM cpe_device")).isEqualTo("2")
            assertThat(scalar("SELECT customer_id||'|'||ssid FROM cpe_device WHERE id='$oldCpe'")).isEqualTo("${original.customer}|A-private")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='$asset'")).isEqualTo("2")
        }
        assertThat(numericAudit(replacement)).isEqualTo(before)
        assertThat(numericTotals(replacement)).isEqualTo("917500|82500|0|9|1|1|10|2")
    }
}
