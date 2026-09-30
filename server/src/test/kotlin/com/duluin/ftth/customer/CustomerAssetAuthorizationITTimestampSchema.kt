package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CustomerAssetAuthorizationITTimestampSchema {
    @Test
    fun `timestamp inventory includes both payload instants and native history audit time`() {
        WarehouseSchemaDatabase().use { database -> database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT table_name||'.'||column_name FROM information_schema.columns
                    WHERE table_schema=current_schema() AND data_type='timestamp with time zone'
                        AND table_name IN ('inventory_deployment_authorization','inventory_deployment_authorization_history')""").use { rows ->
                    val fields = buildList { while (rows.next()) add(rows.getString(1)) }
                    assertThat(fields).containsExactlyInAnyOrder("inventory_deployment_authorization.created_at",
                        "inventory_deployment_authorization.consumed_at", "inventory_deployment_authorization_history.recorded_at")
                }
            }
        } }
    }

    @ParameterizedTest
    @ValueSource(strings=["EQUIVALENT", "CHANGED", "MISSING", "NULL", "MALFORMED"])
    fun `future nullable timestamptz fields participate in semantic comparison`(scenario: String) {
        WarehouseSchemaDatabase().use { database ->
            database.ownerFixture { owner -> owner.createStatement().use {
                it.execute("ALTER TABLE inventory_deployment_authorization ADD COLUMN expires_at timestamptz")
            } }
            database.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                val tenant = UUID.randomUUID()
                statement.execute("SET app.tenant_id='$tenant'")
                statement.execute("SET TIME ZONE 'Australia/Lord_Howe'")
                val expires = if (scenario == "NULL") "null" else "\"2026-07-21T14:00:00Z\""
                val snapshot = when (scenario) {
                    "EQUIVALENT" -> "jsonb_set(to_jsonb(input.permit),'{expires_at}','\"2026-07-21T19:45:00+05:45\"')"
                    "CHANGED" -> "jsonb_set(to_jsonb(input.permit),'{expires_at}','\"2026-07-21T14:00:01Z\"')"
                    "MISSING" -> "to_jsonb(input.permit)-'expires_at'"
                    "NULL" -> "to_jsonb(input.permit)"
                    "MALFORMED" -> "jsonb_set(to_jsonb(input.permit),'{expires_at}','\"not-a-date\"')"
                    else -> error("Unknown future timestamp case")
                }
                statement.executeQuery("""WITH input AS (SELECT jsonb_populate_record(NULL::inventory_deployment_authorization,
                    '{"tenant_id":"$tenant","created_at":"2026-07-20T14:00:00Z","expires_at":$expires}'::jsonb) AS permit)
                    SELECT warehouse_authorization_snapshot_matches($snapshot,input.permit) FROM input""").use { rows ->
                    check(rows.next())
                    assertThat(rows.getBoolean(1)).isEqualTo(scenario in setOf("EQUIVALENT", "NULL"))
                }
            } }
        }
    }
}
