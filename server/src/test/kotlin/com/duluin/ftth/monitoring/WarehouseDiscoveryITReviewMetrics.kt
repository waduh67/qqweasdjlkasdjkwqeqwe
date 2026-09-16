package com.duluin.ftth.monitoring

import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import com.duluin.ftth.customer.LegacyOnuTestFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITReviewMetrics : CustomerAssetEpisodeFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["REPARENT", "RETIME", "FOREIGN_REFERENCE"])
    fun `DB-1 app role cannot invent or rewrite metric attribution`(scenario: String) {
        val fixture = episodeCase()
        val start = Instant.now().minusSeconds(60)
        val boundary = start.plusSeconds(30)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(first, start = start.toString())); sql(fixture.episode(first, start = start.toString())) }
        fixture.stock.transaction {
            sql("UPDATE onu SET retired_at='$boundary',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
            sql("UPDATE inventory_asset_assignment SET ended_at='$boundary',revision=revision+1 WHERE id='$first'")
        }
        fixture.stock.transaction { sql(fixture.assignment(second, fixture.customerB, boundary.toString())); sql(fixture.episode(second, fixture.customerB, boundary.toString())) }
        val firstOnu = fixture.stock.transaction { scalar("SELECT id FROM onu WHERE assignment_id='$first'") }
        val secondOnu = fixture.stock.transaction { scalar("SELECT id FROM onu WHERE assignment_id='$second'") }
        fixture.stock.transaction { sql("INSERT INTO onu_metric(time,tenant_id,onu_id,status) VALUES ('${boundary.minusSeconds(1)}','$tenant','$firstOnu','ONLINE')") }
        val foreign = episodeCase()
        val foreignOnu = LegacyOnuTestFixture.stage(foreign.customerA.toString(), "FOREIGN-${UUID.randomUUID()}")
        val query = when (scenario) {
            "REPARENT" -> "UPDATE onu_metric SET onu_id='$secondOnu' WHERE onu_id='$firstOnu'"
            "RETIME" -> "UPDATE onu_metric SET time='${boundary.plusSeconds(1)}' WHERE onu_id='$firstOnu'"
            "FOREIGN_REFERENCE" -> "INSERT INTO onu_metric(time,tenant_id,onu_id,status) VALUES (clock_timestamp(),'${fixture.stock.tenant}','$foreignOnu','ONLINE')"
            else -> error("Unknown probe")
        }
        val failure = assertThrows<Exception> { fixture.stock.transaction { sql(query) } }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isIn("23503", "23514")
    }
}
