package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

abstract class CustomerAssetReplacementIntegrityCases : CustomerAssetReplacementScenarios() {
    @ParameterizedTest
    @ValueSource(strings = ["MISSING_LEG", "EXTRA_LEG", "SUBSTITUTED_LEG", "FAKE_RELATION", "REPARENT_EPISODE", "TITLE",
        "AVAILABLE", "WRONG_TENANT", "DELETE_HISTORY", "DELETE_OUTBOX", "OUTBOX_SUBSTITUTE", "DELIVERY_TERMINAL"])
    fun `application role cannot corrupt committed replacement facts`(mutation: String) {
        val swap = swappedCase()
        val stock = fixture(swap.case.replacement.stock.token)
        val before = physicalFingerprint(swap.case.old)
        val asset = swap.case.old.installation.receipt.input.lines.single().stockIdentityId

        assertThrows<Exception> {
            stock.transaction {
                when (mutation) {
                    "MISSING_LEG" -> sql("DELETE FROM inventory_movement_leg WHERE movement_id=(SELECT id FROM inventory_movement WHERE operation_id='${swap.operation}') AND direction='OUT'")
                    "EXTRA_LEG" -> sql("""INSERT INTO inventory_movement_leg SELECT (jsonb_populate_record(NULL::inventory_movement_leg,
                        to_jsonb(leg)||jsonb_build_object('id','${UUID.randomUUID()}'))).* FROM inventory_movement_leg leg
                        WHERE movement_id=(SELECT id FROM inventory_movement WHERE operation_id='${swap.operation}') AND direction='IN'""")
                    "SUBSTITUTED_LEG" -> sql("UPDATE inventory_movement_leg SET stock_identity_id='${swap.case.replacement.input.lines.single().stockIdentityId}' WHERE movement_id=(SELECT id FROM inventory_movement WHERE operation_id='${swap.operation}')")
                    "FAKE_RELATION" -> sql("""INSERT INTO inventory_asset_removal SELECT (jsonb_populate_record(NULL::inventory_asset_removal,
                        to_jsonb(removal)||jsonb_build_object('id','${UUID.randomUUID()}','operation_key','fake'))).* FROM inventory_asset_removal removal WHERE id='${swap.operation}'""")
                    "REPARENT_EPISODE" -> sql("UPDATE onu SET customer_id='${UUID.randomUUID()}' WHERE id='${swap.case.old.installation.operation}'")
                    "TITLE" -> sql("UPDATE inventory_serialized_asset SET legal_owner='CUSTOMER',revision=revision+1 WHERE id='$asset'")
                    "AVAILABLE" -> sql("UPDATE inventory_serialized_asset SET status='AVAILABLE',condition='SERVICEABLE',revision=revision+1 WHERE id='$asset'")
                    "WRONG_TENANT" -> sql("UPDATE inventory_asset_removal SET tenant_id='${UUID.randomUUID()}' WHERE id='${swap.operation}'")
                    "DELETE_HISTORY" -> sql("DELETE FROM inventory_asset_assignment_history WHERE assignment_id='${swap.case.old.installation.operation}'")
                    "DELETE_OUTBOX" -> sql("DELETE FROM fulfillment_asset_outbox WHERE operation_id='${swap.operation}'")
                    "OUTBOX_SUBSTITUTE" -> sql("UPDATE fulfillment_asset_outbox SET new_onu_id=old_onu_id WHERE operation_id='${swap.operation}'")
                    "DELIVERY_TERMINAL" -> sql("UPDATE fulfillment_asset_delivery SET state='SUCCEEDED',completed_at=clock_timestamp(),revision=revision+1 WHERE operation_id='${swap.operation}'")
                    else -> error("Unknown mutation")
                }
            }
        }

        assertThat(physicalFingerprint(swap.case.old)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "CLEARED", "MISMATCHED", "RESTORED", "SELECTIVE"])
    fun `recovery final state validators enforce their own tenant context`(timing: String) {
        val swap = swappedCase()
        val stock = fixture(swap.case.replacement.stock.token)
        val run = {
            stock.transaction {
                sql("UPDATE inventory_balance_projection SET quantity_base=quantity_base,revision=revision+1 WHERE stock_identity_id='${swap.case.old.installation.receipt.input.lines.single().stockIdentityId}'")
                when (timing) {
                    "CLEARED" -> sql("SET LOCAL app.tenant_id=''")
                    "MISMATCHED" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    "NORMAL", "SELECTIVE" -> Unit
                    else -> error("Unknown timing")
                }
                sql(if (timing == "SELECTIVE") "SET CONSTRAINTS warehouse_asset_removal_final IMMEDIATE" else "SET CONSTRAINTS ALL IMMEDIATE")
            }
        }

        if (timing in setOf("CLEARED", "MISMATCHED")) assertThrows<Exception> { run() } else run()

        stock.transaction { assertThat(scalar("SELECT count(*) FROM inventory_asset_removal WHERE id='${swap.operation}'")).isEqualTo("1") }
    }
}
