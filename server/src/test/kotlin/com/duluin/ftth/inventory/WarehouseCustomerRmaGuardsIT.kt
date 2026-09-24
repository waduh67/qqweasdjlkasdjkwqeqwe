package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

class WarehouseCustomerRmaGuardsIT : WarehouseCustomerRmaFixture() {
    @Test fun `RMA rejects a substituted serial and non-repair work order before dispatch`() {
        val case = prepareRma()
        val wrongWork = workOrder(case.repair.token, "PSB", case.customer.toString())
        assign(case.repair.token, wrongWork, case.receipt.receiver.second)
        for ((suffix, body) in listOf("serial" to case.body.replace(case.repair.serial, "UNRELATED-SERIAL"),
            "work" to case.body.replace(case.work, wrongWork))) {
            val result = request("POST", "${case.repair.path}/rma-handover", case.repair.token, body, "invalid-rma-$suffix")
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        }
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_rma_handover")).isEqualTo("0")
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner) FROM inventory_serialized_asset WHERE id='${case.repair.asset}'"))
                .isEqualTo("QUARANTINE|CUSTOMER")
        }
        dispatchRma(case)
    }

    @Test fun `RMA acknowledgement and read replay require current location scope`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        receiveRma(case, outbound)
        val path = "/api/v1/warehouse/rma-handovers/${outbound.path("id").asString()}"
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receipt.receiver.second}/${case.receipt.field}", case.repair.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", path, case.receipt.receiver.first).status).isEqualTo(404)
        assertThat(request("POST", "$path/acknowledge", case.receipt.receiver.first, case.ack, "rma-ack").status).isEqualTo(404)
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='${outbound.path("id").asString()}'")).isEqualTo("2")
        }
    }

    @Test fun `app role cannot rewrite RMA source or acknowledge without custody posting`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        val id = outbound.path("id").asString()
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                for (mutation in listOf("UPDATE inventory_rma_handover SET body='{}' WHERE id='$id'",
                    """INSERT INTO inventory_rma_receipt(tenant_id,handover_id,actor_id,request)
                        VALUES ('$tenant','$id','${case.receipt.receiver.second}','${case.ack}'::jsonb)""")) {
                    val point = connection.setSavepoint()
                    val failure = runCatching { connection.createStatement().use { statement ->
                        statement.execute(mutation)
                        statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    } }.exceptionOrNull()
                    connection.rollback(point)
                    assertThat(failure).isInstanceOf(SQLException::class.java)
                    assertThat((failure as SQLException).sqlState).isEqualTo("23514")
                }
            }
            assertThat(scalar("SELECT count(*) FROM inventory_rma_receipt")).isEqualTo("0")
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$id'")).isEqualTo("DISPATCHED")
        }
        receiveRma(case, outbound)
    }
}
