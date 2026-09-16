package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import com.duluin.ftth.monitoring.application.service.IngestBatchRetention
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITReviewRetention : WarehouseDiscoveryFixture() {
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var retention: IngestBatchRetention

    @Test
    fun `populated 175100 batch upgrade preserves mismatched legacy rows but rejects new mismatch`() {
        WarehouseSchemaDatabase("175.100").use { database ->
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val collector = UUID.randomUUID()
            var original = ""
            database.ownerFixture { connection ->
                execute(connection, "INSERT INTO tenant(id,slug,name) VALUES ('$first','first','First'),('$second','second','Second')")
                execute(connection, "INSERT INTO collector(id,tenant_id,name,api_key_hash,api_key_hint) VALUES ('$collector','$first','Original','${"a".repeat(64)}','test')")
                execute(connection, "INSERT INTO ingest_batch(batch_id,collector_id,tenant_id,reading_count,received_at) VALUES ('old-mismatch','$collector','$second',1,clock_timestamp()-interval '30 days')")
                original = value(connection, "SELECT to_jsonb(marker)::text FROM ingest_batch marker WHERE batch_id='old-mismatch'")
            }
            database.migrate("175.101")
            database.dataSource.connection.use { connection ->
                execute(connection, "SET app.tenant_id='$second'")
                assertThat(value(connection, "SELECT to_jsonb(marker)::text FROM ingest_batch marker WHERE batch_id='old-mismatch'")).isEqualTo(original)
                val failure = assertThrows<SQLException> { execute(connection,
                    "INSERT INTO ingest_batch(batch_id,collector_id,tenant_id,reading_count) VALUES ('new-mismatch','$collector','$second',1)") }
                assertThat(failure.sqlState).isEqualTo("23503")
            }
            database.migrate()
            database.dataSource.connection.use { connection ->
                execute(connection, "SET app.tenant_id='$second'")
                assertThat(value(connection, "SELECT (to_jsonb(marker)-'retention_anchor')::text FROM ingest_batch marker WHERE batch_id='old-mismatch'")).isEqualTo(original)
                execute(connection, "DELETE FROM ingest_batch WHERE greatest(received_at,retention_anchor)<clock_timestamp()-interval '72 hours 5 minutes'")
                assertThat(value(connection, "SELECT count(*) FROM ingest_batch WHERE batch_id='old-mismatch'")).isEqualTo("1")
            }
        }
    }

    @Test
    fun `real retention preserves exact boundary and other tenant markers`() {
        val token = newTenantAdmin("boundarymarker")
        val other = newTenantAdmin("othermarker")
        newCollector(token)
        newCollector(other)
        val tenant = tenantId(token)
        val foreign = tenantId(other)
        val collector = scalar(token, "SELECT id FROM collector WHERE tenant_id='$tenant'")
        val otherCollector = scalar(other, "SELECT id FROM collector WHERE tenant_id='$foreign'")
        val boundary = Instant.now().truncatedTo(ChronoUnit.MICROS)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            execute(connection, "SET LOCAL app.tenant_id='$tenant'")
            for ((key, time) in listOf("old" to boundary.minusNanos(1000), "edge" to boundary, "new" to boundary.plusNanos(1000))) {
                execute(connection, "INSERT INTO ingest_batch(batch_id,collector_id,tenant_id,reading_count,received_at,retention_anchor) VALUES ('$key','$collector','$tenant',1,'$time','$time')")
            }
            connection.commit()
            execute(connection, "SET LOCAL app.tenant_id='$foreign'")
            execute(connection, "INSERT INTO ingest_batch(batch_id,collector_id,tenant_id,reading_count,received_at,retention_anchor) VALUES ('foreign','$otherCollector','$foreign',1,'${boundary.minusSeconds(1)}','${boundary.minusSeconds(1)}')")
            connection.commit()
        }
        assertThat(TenantContext.runAs(tenant) { retention.purge(boundary) }).isEqualTo(1)
        assertThat(scalar(token, "SELECT count(*) FROM ingest_batch")).isEqualTo("2")
        assertThat(scalar(other, "SELECT count(*) FROM ingest_batch")).isEqualTo("1")
    }

    @Test
    fun `metric upgrade retains unresolved legacy bytes and chunk retention preserves parents`() {
        WarehouseSchemaDatabase("175.101").use { database ->
            val tenant = UUID.randomUUID()
            val customer = UUID.randomUUID()
            val onu = UUID.randomUUID()
            val unknown = UUID.randomUUID()
            var original = ""
            database.ownerFixture { connection ->
                execute(connection, "INSERT INTO tenant(id,slug,name) VALUES ('$tenant','retained','Retained')")
                execute(connection, "SET app.tenant_id='$tenant'")
                execute(connection, "INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$customer','$tenant','LEGACY','Legacy','Test')")
                execute(connection, "INSERT INTO onu(id,tenant_id,customer_id,serial_number,warehouse_admission) VALUES ('$onu','$tenant','$customer','LEGACY-RETAINED','LEGACY_UNRESOLVED')")
                execute(connection, "INSERT INTO onu_metric(time,tenant_id,onu_id,status) VALUES (clock_timestamp()-interval '365 days','$tenant','$unknown','ONLINE')")
                original = value(connection, "SELECT to_jsonb(metric)::text FROM onu_metric metric")
            }
            database.migrate()
            database.dataSource.connection.use { connection ->
                execute(connection, "SET app.tenant_id='$tenant'")
                assertThat(value(connection, "SELECT (to_jsonb(metric)-ARRAY['attribution_verified','attribution','attribution_topology_revision'])::text FROM onu_metric metric")).isEqualTo(original)
                assertThat(value(connection, "SELECT attribution_verified FROM onu_metric")).isIn("f", "false")
                assertThat(assertThrows<SQLException> { execute(connection, "UPDATE onu_metric SET status='LOS'") }.sqlState).isEqualTo("23514")
                execute(connection, "INSERT INTO onu_metric(time,tenant_id,onu_id,status) VALUES (clock_timestamp(),'$tenant','$onu','ONLINE')")
            }
            database.ownerFixture { connection ->
                execute(connection, "SET app.tenant_id='$tenant'")
                execute(connection, "SELECT drop_chunks('${database.schema}.onu_metric',older_than=>clock_timestamp()-interval '90 days')")
                assertThat(value(connection, "SELECT count(*) FROM onu_metric")).isEqualTo("1")
                assertThat(value(connection, "SELECT count(*) FROM onu WHERE id='$onu'")).isEqualTo("1")
                assertThat(value(connection, "SELECT count(*) FROM onu_topology_history WHERE onu_id='$onu'")).isEqualTo("1")
                assertThat(value(connection, "SELECT count(*) FROM customer_onu_observation_path WHERE onu_id='$onu'")).isEqualTo("1")
            }
        }
    }

    private fun execute(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql); Unit }
    private fun value(connection: Connection, sql: String): String = connection.createStatement().use { query ->
        query.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
