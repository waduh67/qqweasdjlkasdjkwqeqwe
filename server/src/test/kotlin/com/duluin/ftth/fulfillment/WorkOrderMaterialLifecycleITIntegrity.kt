package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkOrderMaterialLifecycleITIntegrity : MaterialLifecycleFixture() {
    @Test fun `app role cannot fabricate closed material with an outstanding residual`() {
        val case = residualCase()

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_material_lifecycle(id,tenant_id,work_order_id,revision,previous_id,actor_id,work_order_revision,
                action,material_state,operation_key,payload_hash,body,cutover_epoch,due_at)
                SELECT '${UUID.randomUUID()}',tenant_id,work_order_id,revision+1,id,actor_id,
                    (SELECT warehouse_revision FROM work_order WHERE id=work_order_id),'CLOSE','CLOSED','forged-close',payload_hash,
                    body,cutover_epoch,due_at FROM inventory_material_lifecycle ORDER BY revision DESC LIMIT 1""")
        } }.hasStackTraceContaining("material obligations prevent closure")
    }

    @Test fun `lifecycle actor references cannot cross tenants`() {
        val case = residualCase()
        val foreign = tenant()
        val actor = mapper.readTree(request("GET", "/api/me", foreign).contentAsString).path("id").asString()

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_material_lifecycle(id,tenant_id,work_order_id,revision,previous_id,actor_id,work_order_revision,
                action,material_state,operation_key,payload_hash,body,cutover_epoch,due_at)
                SELECT '${UUID.randomUUID()}',tenant_id,work_order_id,revision+1,id,'$actor',
                    (SELECT warehouse_revision FROM work_order WHERE id=work_order_id),'REWORK','OPEN','foreign-actor',payload_hash,
                    body,cutover_epoch,due_at FROM inventory_material_lifecycle ORDER BY revision DESC LIMIT 1""")
        } }.hasStackTraceContaining("foreign key")
    }

    @Test fun `app role cannot append a quantitatively fabricated obligation revision`() {
        val case = residualCase()
        val id = UUID.randomUUID()

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_material_lifecycle(id,tenant_id,work_order_id,revision,previous_id,actor_id,work_order_revision,
                action,material_state,operation_key,payload_hash,body,cutover_epoch,due_at)
                SELECT '$id',tenant_id,work_order_id,revision+1,id,actor_id,
                    (SELECT warehouse_revision FROM work_order WHERE id=work_order_id),'REWORK','OPEN','forged-revision',payload_hash,
                    jsonb_set(body::jsonb,'{revision}',to_jsonb(revision+1))::text,cutover_epoch,due_at FROM inventory_material_lifecycle ORDER BY revision DESC LIMIT 1""")
            sql("""INSERT INTO inventory_material_obligation_snapshot(id,tenant_id,lifecycle_id,issue_line_id,stock_identity_id,base_unit,
                issued_base,used_base,returned_base,transferred_base,disposed_base,accountable_base,transit_base,acknowledged_base)
                SELECT '${UUID.randomUUID()}',tenant_id,'$id',issue_line_id,stock_identity_id,base_unit,issued_base,0,0,0,0,issued_base,0,acknowledged_base
                FROM inventory_material_obligation_snapshot LIMIT 1""")
        } }.isInstanceOf(Exception::class.java)
    }

    @ParameterizedTest @ValueSource(strings = ["delete", "quantity", "custody", "available"])
    fun `app role cannot fabricate residual positions`(mode: String) {
        val case = residualCase()
        val id = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, id).status).isEqualTo(200)
        val before = usageAccounting(case.usage)

        val mutation = when (mode) {
            "delete" -> "DELETE FROM inventory_balance_projection WHERE status='QUARANTINE'"
            "quantity" -> "UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE status='QUARANTINE'"
            "custody" -> "UPDATE inventory_balance_projection SET custody_owner_id='${case.usage.receipt.receiver.second}',revision=revision+1 WHERE status='QUARANTINE'"
            "available" -> "UPDATE inventory_balance_projection SET status='AVAILABLE',condition='SERVICEABLE',revision=revision+1 WHERE status='QUARANTINE'"
            else -> error("Unknown test mode")
        }

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction { sql(mutation) } }.isInstanceOf(Exception::class.java)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["inventory_material_lifecycle", "inventory_material_obligation", "inventory_material_obligation_snapshot", "inventory_material_residual", "inventory_material_residual_ack"])
    fun `app role cannot delete lifecycle obligations or receipts`(table: String) {
        val case = residualCase()
        val id = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, id).status).isEqualTo(200)

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction { sql("DELETE FROM $table") } }.isInstanceOf(Exception::class.java)
    }
}
