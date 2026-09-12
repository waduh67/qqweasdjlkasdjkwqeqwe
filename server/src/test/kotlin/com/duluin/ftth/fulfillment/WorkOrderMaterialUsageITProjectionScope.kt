package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialUsageITProjectionScope : MaterialUsageProjectionFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["zero:correct", "zero:cleared", "zero:foreign", "zero:restored", "zero:selective",
        "delete:correct", "delete:cleared", "delete:foreign", "delete:restored", "delete:selective",
        "direct-child:correct", "direct-child:cleared", "direct-child:foreign", "direct-child:restored", "direct-child:selective"])
    fun `each consumed truth validator retains its captured tenant and final obligation`(scenario: String) {
        val (mutation, scope) = scenario.split(':')
        val case = consumedCase()
        val before = usageAccounting(case.usage)

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            if (scope == "selective") sql("SET CONSTRAINTS warehouse_aaa_deferred_tenant_scope IMMEDIATE")
            changeProjection(case, mutation)
            when (scope) {
                "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
                "foreign" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                "correct" -> Unit
                else -> error("Unknown scope")
            }
            sql("SET CONSTRAINTS warehouse_consumed_truth_bound IMMEDIATE")
        } }.hasStackTraceContaining(if (scope in setOf("correct", "restored")) "consumed usage" else "row tenant scope")

        assertThat(usageAccounting(case.usage)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["zero-restore", "delete-reinsert", "replace-id"])
    fun `restored tenant may commit an exact restored consumed projection`(mode: String) {
        val case = consumedCase()
        val before = usageAccounting(case.usage)

        fixture(case.usage.receipt.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            changeProjection(case, mode)
            sql("SET CONSTRAINTS warehouse_aaa_deferred_tenant_scope IMMEDIATE")
            sql("SET LOCAL app.tenant_id=''")
            sql("SET LOCAL app.tenant_id='$tenant'")
            sql("SET CONSTRAINTS warehouse_consumed_truth_bound IMMEDIATE")
        }

        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        assertThat(use(case.usage).contentAsString).isEqualTo(case.body)
    }
}
