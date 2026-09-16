package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.fulfillment.CustomerAssetWorkflowService
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class WarehouseDiscoveryITProvisioning : CustomerDeploymentFixture() {
    private fun discover(installation: Installation, serial: String = requireNotNull(installation.receipt.input.lines.single().serial)): String {
        val stock = fixture(installation.receipt.stock.token)
        stock.transaction {
            context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
                listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now())))
        }
        return stock.transaction { scalar("SELECT id FROM discovered_onu WHERE serial_number='${serial.uppercase()}'") }
    }

    @Test
    fun `issued discovered serial maps exactly once through HTTP with original replay`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val discovered = discover(installation)
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,
            "odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        val path = "/api/monitoring/discovered-onus/$discovered/provision"
        val first = request("POST", path, installation.receipt.receiver.first, body, "discovered-use")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val replay = request("POST", path, installation.receipt.receiver.first, body, "discovered-use")
        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        fixture(installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("2")
        }
    }

    @Test
    fun `issued authorization cannot adopt a different observed serial`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val discovered = discover(installation, "FOREIGN-OBSERVATION")
        val response = request("POST", "/api/monitoring/discovered-onus/$discovered/provision", installation.receipt.receiver.first,
            """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,
                "odpId":null,"portNumber":null,"installRxPowerDbm":null}""", "foreign-observation")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertUninstalled(installation)
    }

    @Test
    fun `automatic fulfillment consumes only an existing issued authorization under its current actor`() {
        val installation = installation()
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        TenantContext.runAs(stock.tenant) {
            val result = context.getBean(CustomerAssetWorkflowService::class.java)
                .installObservedAutomatically(serial, installation.customer, null, "automatic-observation")
            assertThat(result.customerId).isEqualTo(installation.customer)
        }
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("2")
        }
    }

    @Test
    fun `automatic fulfillment cannot use authorization after actor revocation`() {
        val installation = installation()
        val stock = fixture(installation.receipt.stock.token)
        assertThat(request("PUT", "/api/users/${installation.receipt.receiver.second}/access", installation.receipt.stock.token,
            """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        assertThatThrownBy { TenantContext.runAs(stock.tenant) {
            context.getBean(CustomerAssetWorkflowService::class.java)
                .installObservedAutomatically(serial, installation.customer, null, "revoked-automatic")
        } }.isInstanceOf(com.duluin.ftth.inventory.WarehouseContractException::class.java)
        assertUninstalled(installation)
    }

    @Test
    fun `concurrent manual discovery retries preserve one assignment and original response`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val discovered = discover(installation)
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,
            "odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val results = List(2) { executor.submit<Pair<Int, String>> {
                ready.countDown()
                check(start.await(20, java.util.concurrent.TimeUnit.SECONDS))
                val response = request("POST", "/api/monitoring/discovered-onus/$discovered/provision", installation.receipt.receiver.first, body, "concurrent-discovery")
                response.status to response.contentAsString
            } }
            check(ready.await(20, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            val responses = results.map { it.get(40, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(responses.map { it.first }).containsExactly(200, 200)
            assertThat(responses[0].second).isEqualTo(responses[1].second)
        }
        fixture(installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM monitoring_discovery_receipt")).isEqualTo("1")
        }
    }

    @Test
    fun `revoked discovery replay denies the original response under current permissions`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val discovered = discover(installation)
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,
            "odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        val path = "/api/monitoring/discovered-onus/$discovered/provision"
        assertThat(request("POST", path, installation.receipt.receiver.first, body, "revoked-replay").status).isEqualTo(200)
        assertThat(request("PUT", "/api/users/${installation.receipt.receiver.second}/access", installation.receipt.stock.token,
            """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        val denied = request("POST", path, installation.receipt.receiver.first, body, "revoked-replay")
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(403)
        assertThat(denied.contentAsString).doesNotContain("firstSeenAt", "lastRxPowerDbm")
        fixture(installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM monitoring_discovery_receipt")).isEqualTo("1")
        }
    }
}
