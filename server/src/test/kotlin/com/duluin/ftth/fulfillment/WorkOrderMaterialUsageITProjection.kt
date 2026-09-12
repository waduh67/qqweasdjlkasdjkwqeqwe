package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WorkOrderMaterialUsageITProjection : MaterialUsageProjectionFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["zero", "delete", "wrong-quantity", "wrong-location", "wrong-owner", "wrong-identity", "direct-child", "deep-child"])
    fun `immutable consumption prevents an inconsistent committed projection or lineage`(mode: String) {
        val case = consumedCase()
        val before = usageAccounting(case.usage)

        val failure = runCatching { fixture(case.usage.receipt.stock.token).transaction { changeProjection(case, mode) } }.exceptionOrNull()

        if (failure == null) {
            println("T16_CONSUMED_PROJECTION mode=$mode committed=true accounting=${usageAccounting(case.usage)} replay=${use(case.usage).status}")
        }
        assertThat(failure).describedAs("$mode must not commit against immutable consumed history").isNotNull()
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        assertThat(use(case.usage).contentAsString).isEqualTo(case.body)
    }

    @ParameterizedTest @ValueSource(strings = ["zero-restore", "delete-reinsert", "replace-id"])
    fun `exact final projection survives temporary zero or replacement in one transaction`(mode: String) {
        val case = consumedCase()
        val before = usageAccounting(case.usage)

        fixture(case.usage.receipt.stock.token).transaction { changeProjection(case, mode) }

        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        assertThat(use(case.usage).contentAsString).isEqualTo(case.body)
    }

    @Test fun `posting rebuild recreates the exact consumed projection within its transaction`() {
        val case = consumedCase()
        val before = usageAccounting(case.usage)

        fixture(case.usage.receipt.stock.token).transaction {
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='${case.identity}' AND status='CONSUMED'")
            val epoch = scalar("SELECT cutover_epoch FROM inventory_operation WHERE id='${case.id}'").toLong()
            context.getBean(WarehousePosting::class.java).rebuild(epoch)
        }

        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        assertThat(use(case.usage).contentAsString).isEqualTo(case.body)
    }
}
