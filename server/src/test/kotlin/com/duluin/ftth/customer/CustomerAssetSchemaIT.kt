package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CustomerAssetSchemaIT : CustomerAssetEpisodeFixture() {
    @Test
    fun `chronological reuse preserves the original ONU customer and assignment`() {
        val fixture = episodeCase()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(first)); sql(fixture.episode(first)) }
        fixture.stock.transaction {
            sql("UPDATE onu SET retired_at='2026-02-01T00:00:00Z',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
            sql("UPDATE inventory_asset_assignment SET ended_at='2026-02-01T00:00:00Z',revision=revision+1 WHERE id='$first'")
        }
        fixture.stock.transaction {
            sql(fixture.assignment(second, fixture.customerB, "2026-02-01T00:00:00Z"))
            sql(fixture.episode(second, fixture.customerB, "2026-02-01T00:00:00Z"))
        }
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${fixture.asset}'")).isEqualTo("2")
            assertThat(scalar("SELECT customer_id::text FROM onu WHERE assignment_id='$first'")).isEqualTo(fixture.customerA.toString())
            assertThat(scalar("SELECT customer_id::text FROM onu WHERE assignment_id='$second' AND retired_at IS NULL")).isEqualTo(fixture.customerB.toString())
        }
    }

    @Test
    fun `overlapping physical assignments are denied`() {
        val fixture = episodeCase()
        fixture.stock.transaction { sql(fixture.assignment(UUID.randomUUID())) }
        rejected("23505") { fixture.stock.transaction { sql(fixture.assignment(UUID.randomUUID(), fixture.customerB)) } }
    }

    @Test
    fun `wrong tenant cannot create an assignment referencing another tenant asset`() {
        val fixture = episodeCase()
        val foreign = fixture(tenant())
        rejected("23514") {
            foreign.transaction { sql(fixture.assignment(UUID.randomUUID()).replace(fixture.stock.tenant.toString(), tenant.toString())) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CUSTOMER", "ASSET", "TENANT", "DELETE_ASSIGNMENT", "DELETE_ONU", "DELETE_CUSTOMER", "LEGACY_INSERT", "OVERLAP_CLOSED", "ONU_CUSTOMER"])
    fun `app role preserves deployment identity and history`(scenario: String) {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(assignment)); sql(fixture.episode(assignment)) }
        val query = when (scenario) {
            "CUSTOMER" -> "UPDATE inventory_asset_assignment SET customer_id='${fixture.customerB}',revision=1 WHERE id='$assignment'"
            "ASSET" -> "UPDATE inventory_asset_assignment SET asset_id='${UUID.randomUUID()}',revision=1 WHERE id='$assignment'"
            "TENANT" -> "UPDATE inventory_asset_assignment SET tenant_id='${UUID.randomUUID()}',revision=1 WHERE id='$assignment'"
            "DELETE_ASSIGNMENT" -> "DELETE FROM inventory_asset_assignment WHERE id='$assignment'"
            "DELETE_ONU" -> "DELETE FROM onu WHERE assignment_id='$assignment'"
            "DELETE_CUSTOMER" -> "DELETE FROM customer WHERE id='${fixture.customerA}'"
            "LEGACY_INSERT" -> "INSERT INTO onu(id,tenant_id,customer_id,serial_number,warehouse_admission) VALUES ('${UUID.randomUUID()}','${fixture.stock.tenant}','${fixture.customerB}','LEGACY','LEGACY_UNRESOLVED')"
            "OVERLAP_CLOSED" -> fixture.assignment(UUID.randomUUID()).replace("started_at)", "started_at,ended_at)").replace("'2026-01-01T00:00:00Z')", "'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z')")
            "ONU_CUSTOMER" -> "UPDATE onu SET customer_id='${fixture.customerB}' WHERE assignment_id='$assignment'"
            else -> error("Unknown scenario")
        }
        val expected = when (scenario) { "LEGACY_INSERT" -> "42501"; "DELETE_CUSTOMER" -> "23503"; else -> "23514" }
        rejected(expected) { fixture.stock.transaction { sql(query) } }
    }

    @ParameterizedTest
    @ValueSource(strings=["CORRECT", "CLEARED", "FOREIGN", "RESTORED"])
    fun `actual final validator reasserts tenant under selective timing`(scope: String) {
        val fixture = episodeCase()
        val write = {
            fixture.stock.transaction {
                sql(fixture.assignment(UUID.randomUUID()))
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS warehouse_asset_episode_final DEFERRED")
                sql("UPDATE inventory_serialized_asset SET revision=revision+1 WHERE id='${fixture.asset}'")
                when (scope) {
                    "CORRECT" -> Unit
                    "CLEARED" -> sql("SET LOCAL app.tenant_id=''")
                    "FOREIGN" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                }
            }
        }
        if (scope in setOf("CLEARED", "FOREIGN")) rejected("23514", write) else write()
    }

    @Test
    fun `same asset concurrent assignment admits exactly one commit without sleeps`() {
        val fixture = episodeCase()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val results = List(2) {
                executor.submit<String> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    try { fixture.stock.transaction { sql(fixture.assignment(UUID.randomUUID())) }; "00000" }
                    catch (failure: Exception) { sqlState(failure) }
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            assertThat(results.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder("00000", "23505")
        }
    }

    private fun rejected(state: String, action: () -> Unit) {
        assertThat(sqlState(assertThrows<Exception> { action() })).isEqualTo(state)
    }

    private fun sqlState(failure: Throwable): String = generateSequence(failure) { it.cause }
        .filterIsInstance<SQLException>().first().sqlState

    @Test
    fun `M04 has tenant isolated assignment handover and authorization storage`() {
        WarehouseSchemaDatabase().use { database ->
            database.dataSource.connection.use { connection ->
                for (table in listOf("inventory_asset_assignment", "inventory_asset_handover", "inventory_deployment_authorization")) {
                    assertThat(scalar(connection, "SELECT count(*) FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname='$table' AND relrowsecurity AND relforcerowsecurity"))
                        .describedAs(table).isEqualTo("1")
                }
            }
        }
    }

    @Test
    fun `upgrade preserves colliding and malformed ONU identities without inventing physical assets`() {
        WarehouseSchemaDatabase("175.47").use { database ->
            val tenant = UUID.randomUUID()
            val customer = UUID.randomUUID()
            database.ownerFixture { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET app.tenant_id='$tenant'")
                    statement.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','episode-$tenant','Episodes')")
                    statement.execute("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$customer','$tenant','A','A','A')")
                    for (raw in listOf(" Serial-A ", "serial-a", "   ")) {
                        statement.execute("INSERT INTO onu(id,tenant_id,customer_id,serial_number) VALUES ('${UUID.randomUUID()}','$tenant','$customer','$raw')")
                    }
                }
            }
            database.migrate()
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET app.tenant_id='$tenant'") }
                assertThat(scalar(connection, "SELECT count(*) FROM onu WHERE customer_id='$customer' AND original_customer_id='$customer' AND warehouse_admission='LEGACY_UNRESOLVED' AND asset_id IS NULL AND assignment_id IS NULL"))
                    .isEqualTo("3")
                assertThat(scalar(connection, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
                assertThat(scalar(connection, "SELECT count(*) FROM inventory_identity_claim WHERE canonical_value='SERIAL-A' AND state='CONFLICT' AND admitted_asset_id IS NULL")).isEqualTo("1")
            }
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
    }

    private fun scalar(connection: Connection, query: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(query).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
