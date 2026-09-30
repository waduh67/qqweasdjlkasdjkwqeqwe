package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

class WarehouseMaterialSettlementIntegrityIT : MaterialLifecycleFixture() {
    @Test fun `accepted return does not allow a close receipt to omit all its historical material lines`() {
        val case = residualCase()
        val admin = case.usage.receipt.stock.token
        val source = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, source).status).isEqualTo(200)
        val received = request("POST", "/api/v1/warehouse/returns", admin,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$source","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"integrity-intake"}""", "integrity-intake")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(201)
        val id = mapper.readTree(received.contentAsString).path("id").asString()
        val accepted = request("POST", "/api/v1/warehouse/returns/$id/inspect", admin,
            """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"SERVICEABLE","destinationLocationId":"${case.usage.receipt.stock.bin}","evidenceReference":"integrity-inspection","resetConfirmed":false}""", "integrity-inspection")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        fixture(admin).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                val point = connection.setSavepoint()
                val rejection = runCatching {
                    connection.createStatement().use { statement ->
                        statement.execute("""INSERT INTO inventory_material_lifecycle(id,tenant_id,work_order_id,revision,previous_id,actor_id,
                            work_order_revision,action,material_state,operation_key,payload_hash,body,cutover_epoch,due_at)
                            SELECT '${UUID.randomUUID()}',tenant_id,work_order_id,revision+1,id,actor_id,
                                (SELECT warehouse_revision FROM work_order WHERE id=work_order_id),
                                'CLOSE','CLOSED','omit-material-lines',payload_hash,
                                jsonb_set(jsonb_set(jsonb_set(jsonb_set(body::jsonb,'{lines}','[]'::jsonb),
                                    '{outstandingBase}','"0"'::jsonb),'{materialState}','"CLOSED"'::jsonb),'{revision}',to_jsonb(revision+1))::text,
                                cutover_epoch,due_at FROM inventory_material_lifecycle
                            WHERE work_order_id='${case.usage.receipt.workOrder}' ORDER BY revision DESC LIMIT 1""")
                        statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }.exceptionOrNull()
                connection.rollback(point)
                assertThat(rejection).withFailMessage("Close history must retain every issued material line after inspection").isInstanceOf(SQLException::class.java)
                assertThat((rejection as SQLException).sqlState).isEqualTo("23514")
            }
        }
    }
}
