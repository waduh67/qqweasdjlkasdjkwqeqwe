package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

class WarehouseSupplierRepairIntegrityIT : WarehouseRepairFixture() {
    @Test fun `app role cannot substitute a repair vendor or claim a return without its physical posting`() {
        val setup = repairSetup()
        val outbound = dispatchRepair(setup)
        val repair = outbound.path("repair").path("id").asString()
        val receiveRevision = outbound.path("revision").asLong() + 1
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                val probes = listOf(
                    "UPDATE inventory_repair_case SET vendor_reference='changed-vendor-document',revision=revision+1 WHERE id='$repair'" to "REPAIR_HISTORY_IMMUTABLE",
                    """UPDATE inventory_repair_case SET state='RETURNED',revision=revision+1,result='REPAIRED',receive_revision=$receiveRevision,
                        receive_request='{}'::jsonb WHERE id='$repair'""" to "REPAIR_CASE_POSTING_REQUIRED")
                for ((mutation, expected) in probes) {
                    val point = connection.setSavepoint()
                    val rejection = runCatching {
                        connection.createStatement().use { statement ->
                            statement.execute(mutation)
                            statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                        }
                    }.exceptionOrNull()
                    connection.rollback(point)
                    assertThat(rejection).isInstanceOf(SQLException::class.java)
                    assertThat((rejection as SQLException).sqlState).isEqualTo("23514")
                    assertThat(rejection.message).contains(expected)
                }
            }
            assertThat(scalar("SELECT concat_ws('|',state,revision) FROM inventory_repair_case WHERE id='$repair'")).isEqualTo("OUTBOUND|0")
            assertThat(scalar("SELECT concat_ws('|',custody_owner_kind,legal_owner) FROM inventory_serialized_asset WHERE id='${setup.asset}'"))
                .isEqualTo("REPAIR|CUSTOMER")
        }
        val inbound = receiveRepair(setup, outbound)
        inspectRepair(setup, inbound)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',state,revision,inspected_return_document_id) FROM inventory_repair_case WHERE id='$repair'"))
                .isEqualTo("CLOSED|2|${setup.returned.id}")
        }
    }
}
