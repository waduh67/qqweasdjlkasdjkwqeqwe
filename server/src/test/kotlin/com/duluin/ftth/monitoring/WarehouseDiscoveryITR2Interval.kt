package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITR2Interval : CustomerAssetEpisodeFixture() {
    @org.junit.jupiter.api.Test
    fun `two connections serialize metric insertion before attempted clipping closure`() {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        val start = Instant.now().minusSeconds(60)
        val end = start.plusSeconds(30)
        fixture.stock.transaction { sql(fixture.assignment(assignment, start = start.toString())); sql(fixture.episode(assignment, start = start.toString())) }
        val response = request("POST", "/api/monitoring/collectors", fixture.token, """{"name":"Concurrent interval","pollIntervalSeconds":60}""")
        assertThat(response.status).isEqualTo(201)
        val collector = UUID.fromString(mapper.readTree(response.contentAsString).path("collector").path("id").asString())
        val inserted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closingPid = java.util.concurrent.atomic.AtomicInteger()
        java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            val metric = pool.submit { fixture.stock.transaction {
                context.getBean(MetricIngestionService::class.java).ingest(collector, tenant, MetricBatch(UUID.randomUUID().toString(), Instant.now(),
                    listOf(OnuReading(fixture.serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, start.plusSeconds(40)))))
                inserted.countDown(); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
            } }
            check(inserted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val closing = pool.submit { fixture.stock.transaction {
                closingPid.set(scalar("SELECT pg_backend_pid()").toInt())
                sql("UPDATE onu SET retired_at='$end',episode_revision=episode_revision+1 WHERE assignment_id='$assignment'")
                sql("UPDATE inventory_asset_assignment SET ended_at='$end',revision=revision+1 WHERE id='$assignment'")
            } }
            try {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                var waiting = false
                while (!waiting && System.nanoTime() < deadline) {
                    waiting = fixture.stock.transaction { scalar("SELECT count(*) FROM pg_locks WHERE pid=${closingPid.get()} AND NOT granted").toInt() > 0 }
                    if (!waiting) Thread.sleep(10)
                }
                assertThat(waiting).isTrue()
            } finally { release.countDown() }
            metric.get(30, java.util.concurrent.TimeUnit.SECONDS)
            val failure = assertThrows<java.util.concurrent.ExecutionException> { closing.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
        }
        fixture.stock.transaction { assertThat(scalar("SELECT count(*) FROM onu WHERE assignment_id='$assignment' AND retired_at IS NULL")).isEqualTo("1") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "EMPTY", "FOREIGN", "RESTORED"])
    fun `interval final guard independently asserts captured tenant at forced evaluation`(mode: String) {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        val start = Instant.now().minusSeconds(60)
        val end = start.plusSeconds(50)
        fixture.stock.transaction {
            sql(fixture.assignment(assignment, start = start.toString())); sql(fixture.episode(assignment, start = start.toString()))
            val onu = scalar("SELECT id FROM onu WHERE assignment_id='$assignment'")
            sql("INSERT INTO onu_metric(time,tenant_id,onu_id,status) VALUES ('${start.plusSeconds(40)}','$tenant','$onu','ONLINE')")
        }
        val close = { fixture.stock.transaction {
            sql("UPDATE onu SET retired_at='$end',episode_revision=episode_revision+1 WHERE assignment_id='$assignment'")
            sql("UPDATE inventory_asset_assignment SET ended_at='$end',revision=revision+1 WHERE id='$assignment'")
            if (mode == "EMPTY" || mode == "RESTORED") sql("SET LOCAL app.tenant_id=''")
            if (mode == "FOREIGN") sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
            if (mode == "RESTORED") sql("SET LOCAL app.tenant_id='$tenant'")
            sql("SET CONSTRAINTS customer_metric_interval_final IMMEDIATE")
        } }
        if (mode in setOf("NORMAL", "RESTORED")) close() else {
            val failure = assertThrows<Exception> { close() }
            assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `DB-R2-3 closure cannot invalidate an accepted point in this or a prior transaction`(sameTransaction: Boolean) {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        val start = Instant.now().minusSeconds(60)
        val end = start.plusSeconds(30)
        fixture.stock.transaction { sql(fixture.assignment(assignment, start = start.toString())); sql(fixture.episode(assignment, start = start.toString())) }
        val response = request("POST", "/api/monitoring/collectors", fixture.token, """{"name":"Intervals","pollIntervalSeconds":60}""")
        assertThat(response.status).isEqualTo(201)
        val collector = UUID.fromString(mapper.readTree(response.contentAsString).path("collector").path("id").asString())
        fun insert() = context.getBean(MetricIngestionService::class.java).ingest(collector, fixture.stock.tenant,
            MetricBatch(UUID.randomUUID().toString(), Instant.now(), listOf(OnuReading(fixture.serial, "OLT-X", null,
                OnuOperationalStatus.ONLINE, -20.0, null, null, null, start.plusSeconds(40)))))
        if (!sameTransaction) fixture.stock.transaction { assertThat(insert().accepted).isEqualTo(1) }
        val failure = assertThrows<Exception> { fixture.stock.transaction {
            if (sameTransaction) assertThat(insert().accepted).isEqualTo(1)
            sql("UPDATE onu SET retired_at='$end',episode_revision=episode_revision+1 WHERE assignment_id='$assignment'")
            sql("UPDATE inventory_asset_assignment SET ended_at='$end',revision=revision+1 WHERE id='$assignment'")
        } }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
    }
}
