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
