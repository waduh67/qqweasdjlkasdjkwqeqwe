package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CustomerAssetAuthorizationITTimestampIntegrity : CustomerAssetAuthorizationTimezoneFixture() {
    @ParameterizedTest
    @ValueSource(strings=["id", "tenant_id", "asset_id", "issue_line_id", "work_order_id", "customer_id", "actor_id", "purpose",
        "ownership_mode", "operation_id", "expected_asset_revision", "expected_work_order_revision", "expected_plan_revision",
        "expected_issue_revision", "expected_assignment_revision", "previous_assignment_id", "authority_epoch", "cutover_epoch",
        "consumed", "revision", "warehouse_admission"])
    fun `semantic timestamp comparison keeps every non-timestamp binding exact`(field: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        fixture.createTimed(id)
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'UTC'")
            assertThat(scalar("""SELECT warehouse_authorization_snapshot_matches(jsonb_set(history.snapshot,'{$field}','"changed"'),permit)::text
                FROM inventory_deployment_authorization permit JOIN inventory_deployment_authorization_history history
                    ON history.tenant_id=permit.tenant_id AND history.authorization_id=permit.id WHERE permit.id='$id'""")).isEqualTo("false")
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["MISSING_CREATED", "MISSING_CONSUMED", "MISSING_BINDING", "EXTRA_KEY", "CREATED_NULL", "CONSUMED_INSTANT",
        "INVALID_DATE", "INVALID_OFFSET", "NO_OFFSET", "RELATIVE_TIME", "NUMERIC_TIME", "OBJECT_TIME", "CHANGED_INSTANT", "DST_OTHER_INSTANT"])
    fun `malformed timestamps and altered snapshot structure cannot become equivalent`(scenario: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        fixture.createTimed(id)
        val changed = when (scenario) {
            "MISSING_CREATED" -> "history.snapshot-'created_at'"
            "MISSING_CONSUMED" -> "history.snapshot-'consumed_at'"
            "MISSING_BINDING" -> "history.snapshot-'operation_id'"
            "EXTRA_KEY" -> "history.snapshot || '{\"expiry\":null}'::jsonb"
            "CREATED_NULL" -> "jsonb_set(history.snapshot,'{created_at}','null')"
            "CONSUMED_INSTANT" -> "jsonb_set(history.snapshot,'{consumed_at}','\"2026-07-20T14:00:00Z\"')"
            "INVALID_DATE" -> "jsonb_set(history.snapshot,'{created_at}','\"2026-02-30T14:00:00Z\"')"
            "INVALID_OFFSET" -> "jsonb_set(history.snapshot,'{created_at}','\"2026-07-20T14:00:00+25:00\"')"
            "NO_OFFSET" -> "jsonb_set(history.snapshot,'{created_at}','\"2026-07-20T14:00:00\"')"
            "RELATIVE_TIME" -> "jsonb_set(history.snapshot,'{created_at}','\"now\"')"
            "NUMERIC_TIME" -> "jsonb_set(history.snapshot,'{created_at}','42')"
            "OBJECT_TIME" -> "jsonb_set(history.snapshot,'{created_at}','{}')"
            "CHANGED_INSTANT" -> "jsonb_set(history.snapshot,'{created_at}','\"2026-07-20T14:00:00.000001Z\"')"
            "DST_OTHER_INSTANT" -> "jsonb_set(history.snapshot,'{created_at}','\"2026-07-20T10:00:00-05:00\"')"
            else -> error("Unknown mutation")
        }
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'Asia/Kathmandu'")
            assertThat(scalar("""SELECT warehouse_authorization_snapshot_matches($changed,permit)::text
                FROM inventory_deployment_authorization permit JOIN inventory_deployment_authorization_history history
                    ON history.tenant_id=permit.tenant_id AND history.authorization_id=permit.id WHERE permit.id='$id'""")).isEqualTo("false")
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CREATED", "CONSUMED_STATE", "HISTORY_TIMESTAMP", "HISTORY_JSON"])
    fun `app role still cannot mutate timestamp history or consumed state inconsistently`(target: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val snapshot = fixture.createTimed(id)
        val query = when (target) {
            "CREATED" -> "UPDATE inventory_deployment_authorization SET created_at=created_at+interval '1 microsecond' WHERE id='$id'"
            "CONSUMED_STATE" -> "UPDATE inventory_deployment_authorization SET consumed=true,revision=1 WHERE id='$id'"
            "HISTORY_TIMESTAMP" -> "UPDATE inventory_deployment_authorization_history SET recorded_at=recorded_at+interval '1 second' WHERE authorization_id='$id'"
            "HISTORY_JSON" -> "UPDATE inventory_deployment_authorization_history SET snapshot=jsonb_set(snapshot,'{created_at}','\"2026-07-20T14:00:01Z\"') WHERE authorization_id='$id'"
            else -> error("Unknown timestamp mutation")
        }
        rejection { fixture.stock.transaction { sql("SET LOCAL TIME ZONE 'UTC'"); sql(query) } }
        fixture.stock.transaction {
            assertThat(scalar("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo(snapshot)
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe"])
    fun `real source invalidation still rejects in every timezone`(zone: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        fixture.createTimed(id)
        assertThat(rejection {
            fixture.stock.transaction {
                sql("SET LOCAL TIME ZONE '$zone'")
                sql("UPDATE inventory_serialized_asset SET status='DISPOSED',revision=revision+1 WHERE id='${fixture.asset}'")
            }
        }.message).contains("retired physical asset")
    }
}
