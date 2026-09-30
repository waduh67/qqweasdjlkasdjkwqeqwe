package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerDeploymentFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDiscoveryITReviewScope : CustomerDeploymentFixture() {
    private fun admitted(): Pair<Installation, String> {
        val serial = "SCOPE-${UUID.randomUUID()}".uppercase()
        val installation = installation(serials = listOf(serial, "$serial-X"))
        assertThat(consume(installation).status).isEqualTo(201)
        val stock = fixture(installation.receipt.stock.token)
        val now = Instant.now()
        TenantContext.runAs(stock.tenant) { context.getBean(CpeSyncService::class.java).sync(listOf(
            AcsDevice("ACS-$serial", serial, null, null, "Vendor", "Model", null, null, now, "original", observedFieldsAt = now))) }
        return installation to stock.transaction { scalar("SELECT id FROM cpe_device") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CORRECT", "EMPTY", "FOREIGN", "RESTORED", "MISSING_SNAPSHOT"])
    fun `actual cpe snapshot final trigger independently enforces selective timing and scope`(mode: String) {
        val (installation, device) = admitted()
        val stock = fixture(installation.receipt.stock.token)
        val write = {
            stock.transaction {
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS cpe_snapshot_final DEFERRED")
                sql("UPDATE cpe_device SET ssid='changed' WHERE id='$device'")
                if (mode != "MISSING_SNAPSHOT") sql("""INSERT INTO cpe_episode_snapshot(tenant_id,device_id,onu_id,assignment_id,assignment_revision,episode_revision,snapshot,observed_fields_at)
                    SELECT d.tenant_id,d.id,d.onu_id,o.assignment_id,a.revision,o.episode_revision,to_jsonb(d),clock_timestamp()
                    FROM cpe_device d JOIN onu o ON o.tenant_id=d.tenant_id AND o.id=d.onu_id
                    JOIN inventory_asset_assignment a ON a.tenant_id=o.tenant_id AND a.id=o.assignment_id WHERE d.id='$device'""")
                if (mode == "EMPTY" || mode == "RESTORED") sql("SET LOCAL app.tenant_id=''")
                if (mode == "FOREIGN") sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                if (mode == "RESTORED") sql("SET LOCAL app.tenant_id='$tenant'")
            }
        }
        if (mode in setOf("CORRECT", "RESTORED")) write() else rejected(write)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CORRECT", "EMPTY", "FOREIGN", "RESTORED"])
    fun `ownership commit fence retains captured scope under selective constraint timing`(mode: String) {
        val installation = installation()
        assertThat(consume(installation).status).isEqualTo(201)
        val stock = fixture(installation.receipt.stock.token)
        val write = {
            stock.transaction {
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS customer_observation_commit_fence DEFERRED")
                sql("UPDATE onu SET status='LOS' WHERE id='${installation.operation}'")
                if (mode == "EMPTY" || mode == "RESTORED") sql("SET LOCAL app.tenant_id=''")
                if (mode == "FOREIGN") sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                if (mode == "RESTORED") sql("SET LOCAL app.tenant_id='$tenant'")
            }
        }
        if (mode in setOf("CORRECT", "RESTORED")) write() else rejected(write)
    }

    @Test
    fun `writer holding ONU does not deadlock current CPE read census`() {
        val (installation, device) = admitted()
        val stock = fixture(installation.receipt.stock.token)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val writer = executor.submit { stock.transaction {
                scalar("SELECT id FROM onu WHERE id='${installation.operation}' FOR UPDATE")
                held.countDown(); check(release.await(30, TimeUnit.SECONDS))
                sql("UPDATE onu SET status='LOS' WHERE id='${installation.operation}'")
            } }
            try {
                check(held.await(10, TimeUnit.SECONDS))
                val reader = executor.submit<Int> { request("GET", "/api/cpe/devices/$device", installation.receipt.stock.token).status }
                assertThat(reader.get(20, TimeUnit.SECONDS)).isEqualTo(200)
            } finally { release.countDown() }
            writer.get(30, TimeUnit.SECONDS)
        }
    }

    private fun rejected(action: () -> Unit) {
        val failure = assertThrows<Exception> { action() }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
    }
}
