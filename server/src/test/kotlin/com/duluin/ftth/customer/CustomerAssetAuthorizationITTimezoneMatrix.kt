package com.duluin.ftth.customer

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CustomerAssetAuthorizationITTimezoneMatrix : CustomerAssetAuthorizationTimezoneFixture() {
    companion object {
        private val zones = listOf("UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe")
        @JvmStatic fun zonePairs() = zones.flatMap { creator -> zones.map { reader -> Arguments.of(creator, reader) } }
        @JvmStatic fun dstCases() = listOf("2026-11-01T01:30:00-04:00", "2026-11-01T01:30:00-05:00",
            "2026-04-05T01:45:00+11:00", "2026-04-05T01:45:00+10:30")
            .flatMap { instant -> zones.map { zone -> Arguments.of(instant, zone) } }
    }

    @ParameterizedTest
    @MethodSource("zonePairs")
    fun `authorization read and source revalidation preserve snapshots across timezone matrix`(creator: String, reader: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val snapshot = fixture.createTimed(id, creator)
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE '$reader'")
            assertThat(scalar("SELECT (warehouse_read_deployment_authorization('$tenant','$id')).id::text")).isEqualTo(id.toString())
            sql("UPDATE inventory_serialized_asset SET revision=revision+1 WHERE id='${fixture.asset}'")
            sql("SET CONSTRAINTS warehouse_authorization_source_final IMMEDIATE")
            assertThat(scalar("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo(snapshot)
        }
    }

    @ParameterizedTest
    @MethodSource("dstCases")
    fun `DST repeated wall times retain their distinct instants`(instant: String, zone: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        fixture.createTimed(id, "America/New_York", instant)
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE '$zone'")
            assertThat(scalar("SELECT ((warehouse_read_deployment_authorization('$tenant','$id')).created_at='$instant'::timestamptz)::text")).isEqualTo("true")
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe"])
    fun `consumed timestamp and old snapshot survive timezone changes`(zone: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val original = fixture.createTimed(id)
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'Asia/Kathmandu'")
            sql("UPDATE inventory_deployment_authorization SET consumed=true,consumed_at='2026-11-01T01:30:00-05:00',revision=1 WHERE id='$id'")
        }
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE '$zone'")
            assertThat(scalar("SELECT ((warehouse_read_deployment_authorization('$tenant','$id')).consumed_at='2026-11-01T06:30:00Z'::timestamptz)::text")).isEqualTo("true")
            assertThat(scalar("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id' AND revision=0")).isEqualTo(original)
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo("2")
        }
    }

    @Test
    fun `reused pooled connections and restarted pool validate the same persisted authorization`() {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val snapshot = fixture.createTimed(id)
        val url = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
        check(System.getenv("WAREHOUSE_QA") == "true" && url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val backendPids = mutableSetOf<Int>()
        repeat(2) { generation ->
            HikariDataSource().apply {
                jdbcUrl = url
                username = requireNotNull(System.getenv("SPRING_DATASOURCE_USERNAME"))
                password = requireNotNull(System.getenv("SPRING_DATASOURCE_PASSWORD"))
                maximumPoolSize = 1
                minimumIdle = 1
                connectionTimeout = 5000
            }.use { pool ->
                val generationPids = mutableSetOf<Int>()
                for (zone in zones) pool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET app.tenant_id='${fixture.stock.tenant}'")
                        statement.execute("SET TIME ZONE '$zone'")
                        statement.executeQuery("SELECT pg_backend_pid(), (warehouse_read_deployment_authorization('${fixture.stock.tenant}','$id')).id::text").use { rows ->
                            check(rows.next())
                            generationPids.add(rows.getInt(1))
                            assertThat(rows.getString(2)).isEqualTo(id.toString())
                        }
                        statement.executeQuery("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id'").use { rows ->
                            check(rows.next()); assertThat(rows.getString(1)).isEqualTo(snapshot)
                        }
                    }
                }
                assertThat(generationPids).describedAs("pool generation $generation").hasSize(1)
                backendPids.addAll(generationPids)
            }
        }
        assertThat(backendPids).hasSize(2)
    }
}
