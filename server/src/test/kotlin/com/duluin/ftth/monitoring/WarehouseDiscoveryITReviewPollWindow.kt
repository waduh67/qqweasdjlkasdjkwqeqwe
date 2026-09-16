package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import com.duluin.ftth.monitoring.application.service.OltReadingPersister
import com.duluin.ftth.monitoring.application.service.ServerSideOltPoller
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.ProbeResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDiscoveryITReviewPollWindow : CustomerAssetEpisodeFixture() {
    @Test
    fun `T4 captured A poll paused before persister never updates replacement B`() {
        val fixture = episodeCase()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val started = Instant.now().minusSeconds(60).toString()
        fixture.stock.transaction { sql(fixture.assignment(first, start = started)); sql(fixture.episode(first, start = started)) }
        val site = request("POST", "/api/sites", fixture.token, """{"code":"S-POLL","name":"Site","location":{"longitude":106.99,"latitude":-6.24}}""")
        assertThat(site.status).withFailMessage(site.contentAsString).isEqualTo(201)
        val siteId = mapper.readTree(site.contentAsString).path("id").asString()
        val olt = request("POST", "/api/olts", fixture.token, """{"siteId":"$siteId","code":"POLL","name":"OLT","vendor":"ZTE","managementIp":"127.0.0.1","snmpCommunity":"owned"}""")
        assertThat(olt.status).isEqualTo(201)
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = object : OltAdapter {
            override val vendor = "ZTE"
            override fun probe(target: OltTarget) = ProbeResult.Reachable("owned", 1)
            override fun pollOnus(target: OltTarget): List<OnuReading> {
                val reading = OnuReading(fixture.serial, target.oltCode, null, OnuOperationalStatus.LOS, -30.0, null, null, null, Instant.now())
                captured.countDown(); check(release.await(20, TimeUnit.SECONDS))
                return listOf(reading)
            }
        }
        val poller = ServerSideOltPoller(context.getBean(NetworkApi::class.java), AdapterRegistry(listOf(adapter)), context.getBean(OltReadingPersister::class.java))
        Executors.newSingleThreadExecutor().use { executor ->
            val polling = executor.submit { TenantContext.runAs(fixture.stock.tenant) { poller.pollTenant(fixture.stock.tenant) } }
            try {
                check(captured.await(10, TimeUnit.SECONDS))
                val boundary = Instant.now().toString()
                fixture.stock.transaction {
                    sql("UPDATE onu SET retired_at='$boundary',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
                    sql("UPDATE inventory_asset_assignment SET ended_at='$boundary',revision=revision+1 WHERE id='$first'")
                }
                fixture.stock.transaction { sql(fixture.assignment(second, fixture.customerB, boundary)); sql(fixture.episode(second, fixture.customerB, boundary)) }
            } finally { release.countDown() }
            polling.get(30, TimeUnit.SECONDS)
        }
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM onu_metric m JOIN onu o ON o.id=m.onu_id WHERE o.assignment_id='$second'")).isEqualTo("0")
            assertThat(scalar("SELECT status FROM onu WHERE assignment_id='$second'")).isEqualTo("PENDING")
            assertThat(scalar("SELECT count(*) FROM alarm WHERE kind LIKE 'ONU_%'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM monitoring_unassigned_observation WHERE reason='POLL_SPANS_TRANSITION'")).isEqualTo("1")
        }
    }
}
