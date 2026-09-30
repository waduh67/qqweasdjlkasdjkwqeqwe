package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDiscoveryITReviewRace : CustomerDeploymentFixture() {
    @MockitoSpyBean private lateinit var discoveries: DiscoveredOnuRepository

    @Test
    fun `DISCOVERY-2 stale HTTP ignore loses to provision with a stable conflict`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
            listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now()))) }
        val id = stock.transaction { UUID.fromString(scalar("SELECT id FROM discovered_onu WHERE serial_number='$serial'")) }
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (Thread.currentThread().name == "review-ignore") {
                read.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            result
        }.`when`(discoveries).findById(id)
        Executors.newSingleThreadExecutor { task -> Thread(task, "review-ignore") }.use { executor ->
            val ignored = executor.submit<Int> { request("POST", "/api/monitoring/discovered-onus/$id/ignore", installation.receipt.receiver.first).status }
            try {
                check(read.await(20, TimeUnit.SECONDS))
                val provisioned = request("POST", "/api/monitoring/discovered-onus/$id/provision", installation.receipt.receiver.first,
                    """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,"odpId":null,"portNumber":null,"installRxPowerDbm":null}""", "race-provision")
                assertThat(provisioned.status).withFailMessage(provisioned.contentAsString).isEqualTo(200)
            } finally { release.countDown() }
            assertThat(ignored.get(30, TimeUnit.SECONDS)).isEqualTo(409)
        }
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM monitoring_discovery_receipt")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_result")).isEqualTo("1")
        }
    }
}
