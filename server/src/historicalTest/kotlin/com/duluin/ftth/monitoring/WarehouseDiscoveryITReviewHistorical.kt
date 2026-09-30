package com.duluin.ftth.monitoring

import com.duluin.ftth.FtthApplication
import com.duluin.ftth.customer.CustomerAssetOwnershipFixture
import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Connection
import java.util.UUID

class WarehouseDiscoveryITReviewHistorical {
    @Test
    fun `DB-3 real 17589 handover and populated CPE upgrade preserves bytes without certifying guessed revision`() {
        WarehouseSchemaDatabase("175.89").use { database ->
            val captured = SpringApplicationBuilder(FtthApplication::class.java).profiles("test").run(
                "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=${database.url}",
                "--spring.flyway.url=${database.url}", "--spring.flyway.schemas=${database.schema}",
                "--spring.flyway.default-schema=${database.schema}", "--spring.flyway.target=175.89",
                "--ftth.bootstrap.seed-demo-tenant=false",
            ).use { context ->
                val mvc = MockMvcBuilders.webAppContextSetup(context as WebApplicationContext)
                    .apply<DefaultMockMvcBuilder>(springSecurity()).build()
                context.beanFactory.registerSingleton("historicalMockMvc", mvc)
                val fixture = HistoricalFixture()
                context.autowireCapableBeanFactory.autowireBean(fixture)
                fixture.capture()
            }
            database.migrate()
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET app.tenant_id='${captured.tenant}'") }
                assertThat(value(connection, "SELECT to_jsonb(device)::text FROM cpe_device device WHERE id='${captured.device}'"))
                    .isEqualTo(captured.bytes)
                assertThat(value(connection, "SELECT revision FROM inventory_asset_assignment WHERE id='${captured.assignment}'")).isEqualTo("1")
                assertThat(value(connection, "SELECT assignment_revision FROM cpe_episode_snapshot WHERE device_id='${captured.device}'")).isEqualTo("0")
                assertThat(value(connection, "SELECT coalesce(to_jsonb(record)->>'revision_evidence','') FROM cpe_episode_snapshot record WHERE device_id='${captured.device}'"))
                    .isEqualTo("LEGACY_UNVERIFIED")
                assertThat(value(connection, "SELECT count(*) FROM cpe_episode_snapshot WHERE device_id='${captured.device}' AND observed_fields_at IS NOT NULL")).isEqualTo("0")
            }
        }
    }

    private data class Captured(val tenant: UUID, val assignment: UUID, val device: UUID, val bytes: String)
    private class HistoricalFixture : CustomerAssetOwnershipFixture() {
        fun capture(): Captured {
            val case = ownershipCase("LOAN")
            assertThat(accept(case).status).isEqualTo(200)
            val stock = fixture(case.installation.receipt.stock.token)
            val device = UUID.randomUUID()
            val serial = requireNotNull(case.installation.receipt.input.lines.single().serial)
            return stock.transaction {
                assertThat(scalar("SELECT revision FROM inventory_asset_assignment WHERE id='${case.installation.operation}'")).isEqualTo("1")
                sql("""INSERT INTO cpe_device(id,tenant_id,genieacs_id,serial_number,customer_id,onu_id,last_inform_at,ssid)
                    VALUES ('$device','$tenant','HISTORICAL-$device','$serial','${case.installation.customer}','${case.installation.operation}',clock_timestamp(),'retained-private')""")
                Captured(tenant, case.installation.operation, device, scalar("SELECT to_jsonb(device)::text FROM cpe_device device WHERE id='$device'"))
            }
        }
    }
    private fun value(connection: Connection, sql: String) = connection.createStatement().use { query ->
        query.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
