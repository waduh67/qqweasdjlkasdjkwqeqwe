package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseDiscoveryITR2Upgrade : CustomerDeploymentFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.109") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.109" }
        }
    }
    @AfterAll fun closeDatabase() { database.close() }

    @Test
    fun `valid original discovery receipt keeps canonical bytes and replays after forward hash upgrade`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
            listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, null, null, null, null, Instant.now()))) }
        val id = stock.transaction { scalar("SELECT id FROM discovered_onu") }
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,"odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        val original = request("POST", "/api/monitoring/discovered-onus/$id/provision", installation.receipt.receiver.first, body, "upgrade-r2")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(200)
        val canonical = stock.transaction { scalar("SELECT canonical_payload FROM inventory_command_identity WHERE id='${installation.operation}'") }
        database.migrate()
        val replay = request("POST", "/api/monitoring/discovered-onus/$id/provision", installation.receipt.receiver.first, body, "upgrade-r2")
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(original.contentAsString)
        stock.transaction { assertThat(scalar("SELECT canonical_payload FROM inventory_command_identity WHERE id='${installation.operation}'")).isEqualTo(canonical) }
    }
}
