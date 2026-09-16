package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseMigrationInventory
import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerAssetEpisodeRevisionUpgradeIT : CustomerAssetReplacementFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.86") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.86" }
        }
    }
    @AfterAll fun closeDatabase() { database.close() }

    @Test
    fun `V86 revision drift remains raw but validated reads and replay reject after upgrade`() {
        val installation = installation()
        assertThat(consume(installation).status).isEqualTo(201)
        val valid = installation()
        val validResponse = consume(valid)
        assertThat(validResponse.status).isEqualTo(201)
        val validStock = fixture(valid.receipt.stock.token)
        validStock.transaction { sql("UPDATE onu SET installed_at=installed_at+interval '1 second' WHERE id='${valid.operation}'") }
        val stock = fixture(installation.receipt.stock.token)
        val legacy = UUID.randomUUID()
        stock.transaction {
            sql("UPDATE onu SET episode_revision=1 WHERE id='${installation.operation}'")
            sql("""INSERT INTO onu_metric(time,tenant_id,onu_id,status,rx_power_dbm,uptime_seconds)
                VALUES(now(),'$tenant','${installation.operation}','ONLINE',-19.25,240)""")
        }
        database.ownerFixture { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET app.tenant_id='${stock.tenant}'")
                statement.execute("INSERT INTO onu(id,tenant_id,customer_id,serial_number,warehouse_admission) VALUES ('$legacy','${stock.tenant}','${installation.customer}','LEGACY-$legacy','LEGACY_UNRESOLVED')")
                statement.execute("UPDATE onu SET episode_revision=7 WHERE id='$legacy'")
            }
        }
        val before = stock.transaction {
            scalar("""SELECT md5((SELECT to_jsonb(onu)::text FROM onu WHERE id='${installation.operation}')||
                (SELECT jsonb_agg(snapshot ORDER BY revision)::text FROM onu_topology_history WHERE onu_id='${installation.operation}')||
                (SELECT response FROM customer_asset_installation WHERE id='${installation.operation}')||
                (SELECT jsonb_agg(to_jsonb(metric)-ARRAY['attribution_verified','attribution','attribution_topology_revision'] ORDER BY time)::text FROM onu_metric metric WHERE onu_id='${installation.operation}'))""")
        }

        WarehouseMigrationInventory.assertUpgrade("175.86", database.migrate())

        stock.transaction {
            assertThat(scalar("""SELECT md5((SELECT to_jsonb(onu)::text FROM onu WHERE id='${installation.operation}')||
                (SELECT jsonb_agg(snapshot ORDER BY revision)::text FROM onu_topology_history WHERE onu_id='${installation.operation}')||
                (SELECT response FROM customer_asset_installation WHERE id='${installation.operation}')||
                (SELECT jsonb_agg(to_jsonb(metric)-ARRAY['attribution_verified','attribution','attribution_topology_revision'] ORDER BY time)::text FROM onu_metric metric WHERE onu_id='${installation.operation}'))""")).isEqualTo(before)
            assertThat(scalar("SELECT count(*) FROM onu_metric WHERE onu_id='${installation.operation}' AND (attribution_verified OR attribution IS NOT NULL)")).isEqualTo("0")
            assertThat(scalar("SELECT episode_revision FROM onu WHERE id='$legacy'")).isEqualTo("7")
            assertThat(scalar("SELECT count(*) FROM customer_onu_episode_event WHERE onu_id='$legacy'")).isEqualTo("0")
        }
        assertThrows<Exception> { stock.transaction { sql("SELECT warehouse_assert_onu_episode_revision('$tenant','${installation.operation}')") } }
        assertThat(consume(installation).status).isEqualTo(409)
        assertThrows<Exception> { stock.transaction { sql("UPDATE onu SET installed_at=clock_timestamp() WHERE id='${installation.operation}'") } }
        assertThat(consume(valid).contentAsString).isEqualTo(validResponse.contentAsString)
        validStock.transaction {
            sql("UPDATE onu SET installed_at=installed_at+interval '1 second' WHERE id='${valid.operation}'")
            assertThat(scalar("SELECT episode_revision||'|'||topology_revision FROM onu WHERE id='${valid.operation}'")).isEqualTo("1|2")
        }
        val order = workOrder(valid.receipt.stock.token, "DISMANTLE", valid.customer.toString())
        assign(valid.receipt.stock.token, order, valid.receipt.receiver.second)
        val evidence = removalEvidence(valid.receipt.receiver.first, order)
        val removed = request("POST", "/api/customers/${valid.customer}/assets/remove", valid.receipt.receiver.first,
            """{"assignmentId":"${valid.operation}","expectedRevision":0,"expectedTitleRevision":0,"workOrderId":"$order","evidenceId":"$evidence"}""", "upgraded-removal")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(removed.contentAsString).path("retired").path("episodeRevision").asLong()).isEqualTo(2)
        validStock.transaction { assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${valid.operation}'")).isEqualTo("2") }
        assertThat(database.migrate().migrationsExecuted).isZero()
    }
}
