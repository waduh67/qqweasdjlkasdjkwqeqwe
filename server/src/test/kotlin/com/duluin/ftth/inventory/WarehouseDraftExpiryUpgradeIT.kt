package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDraftExpiryUpgradeIT {
    @Test fun `legacy backfill bounds document and plan dates after draining old writers without rewriting source or replay`() {
        WarehouseSchemaDatabase("178.11").use { database ->
            database.ownerFixture { connection -> connection.createStatement().use { sql ->
                // Seed historical timestamps at INSERT; never rewrite sealed
                // sources or disable their existing immutable guards.
                sql.execute("""CREATE FUNCTION warehouse_qa_legacy_draft_timestamp() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                    DECLARE ordinal integer; BEGIN
                    IF TG_TABLE_NAME='inventory_document' THEN
                        IF NEW.kind<>'TRANSFER' THEN RETURN NEW; END IF;
                        SELECT count(*) INTO ordinal FROM inventory_document WHERE tenant_id=NEW.tenant_id AND kind='TRANSFER';
                    ELSE SELECT count(*) INTO ordinal FROM inventory_material_plan WHERE tenant_id=NEW.tenant_id;
                    END IF;
                    NEW.updated_at:=CASE ordinal WHEN 0 THEN clock_timestamp()-interval '30 days'
                        WHEN 1 THEN clock_timestamp()+interval '30 days' WHEN 2 THEN 'infinity'::timestamptz
                        WHEN 3 THEN '-infinity'::timestamptz WHEN 4 THEN clock_timestamp()-interval '2 days'
                        ELSE clock_timestamp() END;
                    RETURN NEW; END ${'$'}${'$'}""")
                for (table in listOf("inventory_document", "inventory_material_plan")) {
                    sql.execute("CREATE TRIGGER warehouse_qa_legacy_timestamp BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION warehouse_qa_legacy_draft_timestamp()")
                }
            } }
            val seed = WarehouseDraftUpgradeProcess.seed(database, "expiry", "178.11")
            database.ownerFixture { connection -> connection.createStatement().use { sql ->
                for (table in listOf("inventory_document", "inventory_material_plan")) sql.execute("DROP TRIGGER warehouse_qa_legacy_timestamp ON $table")
                sql.execute("DROP FUNCTION warehouse_qa_legacy_draft_timestamp()")
            } }
            var before = ""
            var writerTime = Instant.EPOCH
            database.ownerFixture { writer ->
                writer.autoCommit = false
                try {
                    // Hold the same relation lock a legacy INSERT/UPDATE needs.
                    // The source timestamp matrix was already sealed by old HTTP.
                    writer.createStatement().use { it.execute("LOCK TABLE inventory_document IN ROW EXCLUSIVE MODE") }
                    before = fingerprint(writer)
                    Executors.newSingleThreadExecutor().use { pool ->
                        try {
                        val entered = CountDownLatch(1)
                        val migration = pool.submit<org.flywaydb.core.api.output.MigrateResult> { entered.countDown(); database.migrate() }
                        check(entered.await(10, TimeUnit.SECONDS))
                        val limit = Instant.now().plusSeconds(20)
                        var blocked = false
                        while (!blocked && Instant.now().isBefore(limit)) {
                            database.ownerFixture { observer -> blocked = value(observer, """SELECT EXISTS(SELECT FROM pg_locks
                                WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database())
                                AND relation='inventory_document'::regclass AND mode='ShareRowExclusiveLock' AND NOT granted)::text""").toBoolean() }
                            if (!blocked) Thread.sleep(25)
                        }
                        check(blocked && !migration.isDone) { "Migration did not wait for the legacy document writer" }
                        writerTime = Instant.parse(value(writer, "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"))
                        writer.commit()
                        assertThat(migration.get(60, TimeUnit.SECONDS).migrations.map { it.version }).containsExactly("178.12")
                        } finally { writer.rollback() }
                    }
                } finally { writer.rollback() }
            }
            database.ownerFixture { connection ->
                assertThat(fingerprint(connection)).isEqualTo(before)
                for ((family, table) in listOf("document" to "inventory_document", "plan" to "inventory_material_plan")) {
                    val collection = seed.path(if (family=="document") "documents" else "plans")
                    collection.forEachIndexed { index, row ->
                        connection.createStatement().use { sql -> sql.executeQuery("""SELECT a.*,source.updated_at,
                            (a.deadline>=a.observed_at AND a.deadline<=a.observed_at+interval '7 days') bounded,
                            (a.deadline=source.updated_at+interval '7 days') retained
                            FROM inventory_${family}_draft_activity a JOIN $table source ON source.id=a.${family}_id
                            WHERE source.id='${id(row)}'""").use { result ->
                            check(result.next())
                            assertThat(result.getString("provenance")).isEqualTo("LEGACY_BASELINE")
                            assertThat(result.getBoolean("bounded")).isTrue()
                            val baseline = result.getTimestamp("observed_at").toInstant()
                            assertThat(baseline).isAfterOrEqualTo(writerTime)
                            when (index) {
                                0, 2, 3 -> assertThat(result.getTimestamp("deadline").toInstant()).isEqualTo(baseline)
                                1 -> assertThat(result.getTimestamp("deadline").toInstant()).isEqualTo(baseline.plusSeconds(604800))
                                else -> assertThat(result.getBoolean("retained")).isTrue()
                            }
                            assertThat(result.next()).isFalse()
                        } }
                    }
                }
            }
            postingContext(database).use { context -> CurrentClient(context).verify(seed) }
            database.ownerFixture { assertThat(fingerprint(it)).isEqualTo(before) }
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
    }

    private class CurrentClient(application: ConfigurableApplicationContext) : WarehouseMasterHttpFixture() {
        init {
            context = application
            mvc = MockMvcBuilders.webAppContextSetup(application as WebApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity()).build()
            ReflectionTestUtils.setField(this, "onboarding", application.getBean(OnboardTenantUseCase::class.java))
        }
        fun verify(seed: JsonNode) {
            val token = seed.path("token").asString()
            seed.path("documents").forEachIndexed { index, row ->
                val id = row.path("id").asString()
                val current = request("GET", "/api/v1/warehouse/transfers/$id", token)
                assertThat(current.status).withFailMessage(current.contentAsString).isEqualTo(200)
                assertThat(mapper.readTree(current.contentAsString).path("state").asString())
                    .isEqualTo(if (index in setOf(0,2,3)) "EXPIRED" else "DRAFT")
                assertThat(request("POST", "/api/v1/warehouse/transfers", token, row.path("body").asString(), row.path("key").asString()).contentAsString)
                    .isEqualTo(row.path("original").asString())
                if (index in setOf(0,2,3)) WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/transfers/$id/dispatch", token, """{"expectedRevision":0}"""))
            }
            seed.path("plans").forEachIndexed { index, row ->
                val path = "/api/work-orders/${row.path("workOrderId").asString()}/materials"
                val current = request("GET", path, token)
                assertThat(current.status).withFailMessage(current.contentAsString).isEqualTo(200)
                assertThat(mapper.readTree(current.contentAsString).path("planState").asString())
                    .isEqualTo(if (index in setOf(0,2,3)) "EXPIRED" else "DRAFT")
                assertThat(mapper.readTree(current.contentAsString).path("plan").path("id")).isEqualTo(row.path("id"))
                assertThat(request("PUT", "$path/plan", token, row.path("body").asString(), row.path("key").asString()).contentAsString)
                    .isEqualTo(row.path("original").asString())
                if (index in setOf(0,2,3)) WarehouseDraftExpiryFacts.rejected(request("POST", "$path/submit-request", token,
                    """{"expectedRevision":1,"workOrderRevision":${row.path("workOrderRevision").asLong()}}"""))
            }
        }
    }

    private fun id(row: JsonNode) = UUID.fromString(row.path("id").asString()).toString()
    private fun value(connection: Connection, query: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(query).use { result -> check(result.next()); result.getString(1) }
    }
    private fun fingerprint(connection: Connection) = value(connection, "SELECT md5(jsonb_build_array(" +
        listOf("inventory_document", "inventory_document_line", "inventory_receipt_intake", "inventory_operation",
            "inventory_command_identity", "inventory_material_plan", "inventory_material_plan_line", "inventory_material_submission",
            "inventory_movement", "inventory_balance_projection").joinToString(",") {
            "(SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text) FROM $it t)"
        } + ")::text)")
}
