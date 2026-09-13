package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialLifecycleITTiming : MaterialLifecycleFixture() {
    @ParameterizedTest @ValueSource(strings = ["normal", "cleared", "mismatched", "restored", "selective"])
    fun `residual final state validator retains its own tenant obligation`(timing: String) {
        val case = residualCase()
        val id = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, id).status).isEqualTo(200)

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            sql("SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS warehouse_residual_position_truth DEFERRED")
            sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE status='QUARANTINE'")
            when (timing) {
                "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
                "mismatched" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                "normal" -> Unit
                else -> error("Unknown timing")
            }
            sql("SET CONSTRAINTS warehouse_residual_position_truth IMMEDIATE")
        } }.hasStackTraceContaining(if (timing in setOf("normal", "restored")) "residual positions must match" else "row tenant scope")
    }

    @ParameterizedTest @ValueSource(strings = ["normal", "restored"])
    fun `restored final quantity remains valid under deferred timing`(timing: String) {
        val case = residualCase()
        val id = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, id).status).isEqualTo(200)

        fixture(case.usage.receipt.stock.token).transaction {
            val position = scalar("SELECT id FROM inventory_balance_projection WHERE status='QUARANTINE' AND quantity_base=17500 AND stock_identity_id='${case.input.stockIdentityId}'")
            sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE id='$position'")
            sql("UPDATE inventory_balance_projection SET quantity_base=17500,revision=revision+1 WHERE id='$position'")
            if (timing == "restored") { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
            sql("SET CONSTRAINTS warehouse_residual_position_truth IMMEDIATE")
        }

        assertThat(settlement(case).status).isEqualTo(200)
    }
}
