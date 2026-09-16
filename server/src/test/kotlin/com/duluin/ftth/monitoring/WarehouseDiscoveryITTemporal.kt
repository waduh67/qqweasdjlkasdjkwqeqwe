package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class WarehouseDiscoveryITTemporal : CustomerAssetEpisodeFixture() {
    @Test
    fun `unseen delayed batch belongs only to closed A not current B`() {
        val fixture = episodeCase()
        val start = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        val boundary = start.plusSeconds(1800)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(first, start = start.toString())); sql(fixture.episode(first, start = start.toString())) }
        fixture.stock.transaction {
            sql("UPDATE onu SET retired_at='$boundary',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
            sql("UPDATE inventory_asset_assignment SET ended_at='$boundary',revision=revision+1 WHERE id='$first'")
        }
        fixture.stock.transaction {
            sql(fixture.assignment(second, fixture.customerB, boundary.toString()))
            sql(fixture.episode(second, fixture.customerB, boundary.toString()))
        }
        val response = request("POST", "/api/monitoring/collectors", fixture.token,
            """{"name":"Temporal","pollIntervalSeconds":60}""")
        assertThat(response.status).isEqualTo(201)
        val collector = UUID.fromString(mapper.readTree(response.contentAsString).path("collector").path("id").asString())
        val sample = OnuReading(fixture.serial, "OLT-X", "1/1/1", OnuOperationalStatus.LOS, -30.0, null, null, null, boundary.minusMillis(1))
        val result = fixture.stock.transaction {
            context.getBean(MetricIngestionService::class.java).ingest(collector, tenant, MetricBatch(UUID.randomUUID().toString(), Instant.now(), listOf(sample)))
        }
        assertThat(result.accepted).isEqualTo(1)
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM onu_metric m JOIN onu o ON o.id=m.onu_id WHERE o.assignment_id='$first'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu_metric m JOIN onu o ON o.id=m.onu_id WHERE o.assignment_id='$second'")).isEqualTo("0")
            assertThat(scalar("SELECT status FROM onu WHERE assignment_id='$second'")).isEqualTo("PENDING")
            assertThat(scalar("SELECT count(*) FROM alarm")).isEqualTo("0")
            val observations = context.getBean(com.duluin.ftth.customer.CustomerObservationApi::class.java)
            assertThat(observations.resolveObservation(fixture.serial.lowercase(), start).episode?.onu?.customerId).isEqualTo(fixture.customerA)
            assertThat(observations.resolveObservation(fixture.serial, boundary).episode?.onu?.customerId).isEqualTo(fixture.customerB)
            assertThat(observations.resolveObservation(fixture.serial, start.minusNanos(1)).episode).isNull()
            assertThat(observations.currentEpisode(fixture.serial)?.onu?.customerId).isEqualTo(fixture.customerB)
        }
    }

    @Test
    fun `stale ACS retains A snapshot without exposing fields to B`() {
        val fixture = episodeCase()
        val start = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        val boundary = start.plusSeconds(1800)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(first, start = start.toString())); sql(fixture.episode(first, start = start.toString())) }
        val snapshot = AcsDevice("ACS-${fixture.asset}", fixture.serial, null, null, "Vendor", "A-model", null, "192.0.2.10", boundary.minusSeconds(10), "A-private")
        val sync = context.getBean(CpeSyncService::class.java)
        TenantContext.runAs(fixture.stock.tenant) { sync.sync(listOf(snapshot)) }
        val oldId = fixture.stock.transaction { scalar("SELECT id FROM cpe_device WHERE customer_id='${fixture.customerA}'") }
        fixture.stock.transaction {
            sql("UPDATE onu SET retired_at='$boundary',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
            sql("UPDATE inventory_asset_assignment SET ended_at='$boundary',revision=revision+1 WHERE id='$first'")
        }
        fixture.stock.transaction { sql(fixture.assignment(second, fixture.customerB, boundary.toString())); sql(fixture.episode(second, fixture.customerB, boundary.toString())) }
        TenantContext.runAs(fixture.stock.tenant) { sync.sync(listOf(snapshot)) }
        fixture.stock.transaction {
            assertThat(context.getBean(CpeApi::class.java).findDevicesForCustomer(fixture.customerB)).isEmpty()
            assertThat(scalar("SELECT customer_id FROM cpe_device WHERE id='$oldId'")).isEqualTo(fixture.customerA.toString())
            assertThat(scalar("SELECT ssid FROM cpe_device WHERE id='$oldId'")).isEqualTo("A-private")
        }
        val fresh = snapshot.copy(lastInformAt = boundary.plusSeconds(1), model = "B-model", ssid = "B-private", ipAddress = "192.0.2.20")
        TenantContext.runAs(fixture.stock.tenant) { sync.sync(listOf(fresh)) }
        TenantContext.runAs(fixture.stock.tenant) { sync.sync(listOf(snapshot)) }
        fixture.stock.transaction {
            val devices = context.getBean(CpeApi::class.java).findDevicesForCustomer(fixture.customerB)
            assertThat(devices).hasSize(1)
            assertThat(devices.single().deviceId.toString()).isNotEqualTo(oldId)
            assertThat(devices.single().model).isEqualTo("B-model")
            assertThat(devices.single().ipAddress).isEqualTo("192.0.2.20")
            assertThat(scalar("SELECT count(*) FROM cpe_device")).isEqualTo("2")
            assertThat(scalar("SELECT ssid FROM cpe_device WHERE id='$oldId'")).isEqualTo("A-private")
            assertThat(context.getBean(com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository::class.java).findById(UUID.fromString(oldId))).isNull()
        }
    }
}
