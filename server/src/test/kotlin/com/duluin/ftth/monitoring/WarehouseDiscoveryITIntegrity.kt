package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITIntegrity : CustomerAssetEpisodeFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["REPARENT", "FORGE_FIELDS", "REWRITE_SNAPSHOT", "DELETE_CACHE", "FORGE_REVISION", "REWRITE_PATH"])
    fun `application role cannot rewrite CPE ownership or immutable observation evidence`(scenario: String) {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        val start = Instant.now().minusSeconds(60).toString()
        fixture.stock.transaction { sql(fixture.assignment(assignment, start = start)); sql(fixture.episode(assignment, start = start)) }
        TenantContext.runAs(fixture.stock.tenant) {
            context.getBean(CpeSyncService::class.java).sync(listOf(AcsDevice("guard-${fixture.asset}", fixture.serial,
                null, null, "Vendor", "Model", null, "192.0.2.1", Instant.now(), "private")))
        }
        val query = when (scenario) {
            "REPARENT" -> "UPDATE cpe_device SET customer_id='${fixture.customerB}'"
            "FORGE_FIELDS" -> "UPDATE cpe_device SET ssid='forged'"
            "REWRITE_SNAPSHOT" -> "UPDATE cpe_episode_snapshot SET snapshot='{}'::jsonb"
            "DELETE_CACHE" -> "DELETE FROM cpe_device"
            "FORGE_REVISION" -> "INSERT INTO cpe_episode_snapshot(tenant_id,device_id,onu_id,assignment_id,assignment_revision,episode_revision,snapshot) SELECT tenant_id,device_id,onu_id,assignment_id,assignment_revision,episode_revision+1,snapshot FROM cpe_episode_snapshot"
            "REWRITE_PATH" -> "UPDATE customer_onu_observation_path SET has_odp=true"
            else -> error("Unknown scenario")
        }
        val failure = assertThrows<Exception> { fixture.stock.transaction { sql(query) } }
        val sqlState = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState
        assertThat(sqlState).isEqualTo("23514")
        fixture.stock.transaction { assertThat(scalar("SELECT ssid FROM cpe_device")).isEqualTo("private") }
    }
}
