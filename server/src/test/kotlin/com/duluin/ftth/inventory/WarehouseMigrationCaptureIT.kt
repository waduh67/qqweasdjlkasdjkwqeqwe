package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMigrationCaptureIT : WarehousePolicyHttpFixture() {
    companion object {
        private val legacy = List(2) { WarehouseMigrationLegacyFixture() }
        private val database = WarehouseSchemaDatabase("172").also { db -> db.ownerFixture { connection -> legacy.forEach { it.seed(connection) } } }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var dataSource: DataSource
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun setup(index: Int): String {
        val old = legacy[index]
        val admin = tenant("migration-${old.tenant}")
        val actor = mapper.readTree(request("GET", "/api/me", admin).contentAsString).path("id").asString()
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.tenant}'")
            sql.execute("UPDATE inventory_location SET name='Gudang lama',area_id='${area(admin)}',revision=revision+1 WHERE id='${old.location}'")
            sql.execute("UPDATE customer SET area_id='${area(admin)}' WHERE id='${old.customer}'")
        } }
        grant(admin, actor, listOf(old.location.toString()))
        return admin
    }

    private fun report(admin: String): JsonNode {
        val response = request("GET", "/api/v1/warehouse/provenance", admin)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
    private fun cases(admin: String) = mapper.readTree(request("GET", "/api/v1/warehouse/provenance/cases", admin).contentAsString).path("items").asSequence().toList()
    private fun beginRequest(admin: String, hash: String, key: String = UUID.randomUUID().toString()) =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/provenance/batches"))
            .header("Authorization", "Bearer $admin").header("Idempotency-Key", key).header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf(
                "expectedEpoch" to 0, "expectedPreservationHash" to hash)))).build()

    @Test fun `exclusive cutoff waits for a real legacy writer and captures its committed source only after rejecting the stale review`() {
        val admin = setup(0)
        val old = legacy[0]
        val before = report(admin)
        val oldCase = cases(admin).single { it.path("sourceId").asString() == old.balance.toString() }
        dataSource.connection.use { writer ->
            writer.autoCommit = false
            try {
                val pid = writer.createStatement().use { sql ->
                    sql.execute("SET LOCAL app.tenant_id='${old.tenant}'")
                    sql.execute("SELECT epoch FROM inventory_tenant_cutover WHERE tenant_id='${old.tenant}' FOR SHARE")
                    sql.execute("UPDATE inventory_balance_projection SET quantity=91000,revision=revision+1 WHERE id='${old.balance}'")
                    sql.executeQuery("SELECT pg_backend_pid()").use { rows -> rows.next(); rows.getInt(1) }
                }
                val attempt = client.sendAsync(beginRequest(admin, before.path("preservationHash").asString()), HttpResponse.BodyHandlers.ofString())
                dataSource.connection.use { observer ->
                    await().atMost(Duration.ofSeconds(15)).until { observer.createStatement().use { sql ->
                        sql.executeQuery("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE $pid=ANY(pg_blocking_pids(pid)))").use { rows -> rows.next(); rows.getBoolean(1) }
                    } }
                }
                assertThat(attempt.isDone).isFalse()
                writer.commit()
                val stale = attempt.get(25, TimeUnit.SECONDS)
                assertThat(stale.statusCode()).withFailMessage(stale.body()).isEqualTo(409)
                assertThat(stale.body()).contains("STALE_REVISION")
            } finally { writer.rollback() }
        }
        val current = report(admin)
        assertThat(current.path("cutover").path("state").asString()).isEqualTo("LEGACY")
        assertThat(current.path("preservationHash")).isNotEqualTo(before.path("preservationHash"))
        val preview = cases(admin).single { it.path("sourceId").asString() == old.balance.toString() }
        assertThat(preview.path("sourceSnapshot").path("legacyQuantity").asString()).isEqualTo("91000")
        assertThat(preview.path("id")).isNotEqualTo(oldCase.path("id"))
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_id='${old.balance}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_migration_batch")).isEqualTo("0")
        }
        val command = beginRequest(admin, current.path("preservationHash").asString())
        val result = client.send(command, HttpResponse.BodyHandlers.ofString())
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201)
        assertThat(mapper.readTree(result.body()).path("batch").path("sourceHash")).isEqualTo(current.path("preservationHash"))
        assertThat(client.send(command, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(result.body())
        assertThat(cases(admin).single { it.path("sourceId").asString() == old.balance.toString() }).isEqualTo(preview)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_id='${old.balance}'")).isEqualTo("2")
            assertThat(scalar("SELECT source_snapshot->>'legacyQuantity' FROM inventory_provenance_case WHERE id='${oldCase.path("id").asString()}'")).isEqualTo("82500")
            assertThat(scalar("SELECT source_snapshot->>'legacyQuantity' FROM inventory_provenance_case WHERE id='${preview.path("id").asString()}'")).isEqualTo("91000")
            assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
        }
        assertThatThrownBy { fixture(admin).transaction {
            sql("""INSERT INTO inventory_provenance_case(id,tenant_id,source_table,source_id,source_snapshot)
                SELECT '${UUID.randomUUID()}',tenant_id,source_table,source_id,source_snapshot FROM inventory_provenance_case
                WHERE id='${preview.path("id").asString()}'""")
        } }.hasStackTraceContaining("source capture requires the exclusive initial validating cutoff")
    }

    @Test fun `current report and batch include added sources and exclude removed projections while keeping the old preserved row`() {
        val admin = setup(1)
        val old = legacy[1]
        val removed = cases(admin).single { it.path("sourceId").asString() == old.balance.toString() }
        val added = UUID.randomUUID()
        val beforeHash = report(admin).path("preservationHash")
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.tenant}'")
            sql.execute("DELETE FROM inventory_balance_projection WHERE id='${old.balance}'")
            sql.execute("""INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,
                status,quantity,rebuilt_at,warehouse_admission) VALUES ('$added','${old.tenant}','${UUID.randomUUID()}','${old.sku}',
                '${old.location}','${old.location}','WAREHOUSE','AVAILABLE',17000,now(),'LEGACY_UNRESOLVED')""")
        } }
        val current = report(admin)
        assertThat(current.path("sourceCount").asInt()).isEqualTo(11)
        assertThat(current.path("preservationHash")).isNotEqualTo(beforeHash)
        assertThat(cases(admin).map { it.path("sourceId").asString() }).contains(added.toString()).doesNotContain(old.balance.toString())
        val response = client.send(beginRequest(admin, current.path("preservationHash").asString()), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201)
        assertThat(mapper.readTree(response.body()).path("batch").path("sourceCount").asInt()).isEqualTo(11)
        fixture(admin).transaction {
            assertThat(scalar("SELECT source_snapshot->>'legacyQuantity' FROM inventory_provenance_case WHERE id='${removed.path("id").asString()}'")).isEqualTo("82500")
            assertThat(scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_id='$added'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM warehouse_report_provenance_case WHERE source_id='${old.balance}'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM warehouse_report_provenance_case WHERE source_id='$added'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
        }
    }
}
