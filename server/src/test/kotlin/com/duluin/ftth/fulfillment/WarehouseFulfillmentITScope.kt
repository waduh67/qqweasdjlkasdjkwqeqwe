package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehouseFulfillmentITScope : WarehouseFulfillmentFixture() {
    @ParameterizedTest @ValueSource(strings = ["cleared", "foreign", "restored", "selective"])
    fun `fulfillment validator checks its own tenant at deferred execution`(scope: String) {
        val case = approvedCable()
        val check = {
            fixture(case.receipt.stock.token).transaction {
                val tenant = scalar("SELECT current_setting('app.tenant_id')")
                if (scope == "selective") sql("SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS warehouse_fulfillment_bound DEFERRED")
                sql("UPDATE fulfillment_checkpoint SET attempts=attempts+1")
                when (scope) {
                    "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
                    "foreign" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    else -> error("Unknown scope")
                }
                sql("SET CONSTRAINTS warehouse_fulfillment_bound IMMEDIATE")
            }
        }

        if (scope == "restored") check() else assertThatThrownBy { check() }.hasStackTraceContaining("row tenant scope")

        fixture(case.receipt.stock.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("1") }
    }

    @ParameterizedTest @ValueSource(strings = ["fulfillment_approval_snapshot", "inventory_material_settlement"])
    fun `foreign tenant cannot read or retenant verification evidence`(table: String) {
        val case = approvedCable()
        val foreign = tenant()
        fixture(foreign).transaction { assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0") }

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction { sql("UPDATE $table SET tenant_id='${UUID.randomUUID()}'") } }
            .isInstanceOf(RuntimeException::class.java)
    }
}
