package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.ReceiptRealStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.Import

@Import(ReceiptRealStorage::class)
class WarehouseReassignmentSettlementIT : WarehouseNumericLifecycleFixture() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `new assigned technician completes and QA verifies the material already posted by the original technician`(includeCable: Boolean) {
        val original = numericCase(includeCable)
        val usage = if (includeCable) numericUse(original) else null
        val installed = numericInstall(original)
        val signature = numericHandover(original, installed)
        if (usage != null) numericReturn(original, usage)
        val replacement = technician(original.stock.token, setOf("customer.onu.assign"))
        assign(original.stock.token, original.workOrder, replacement.second)
        val before = numericAudit(original)
        if (usage != null) {
            val revoked = request("POST", usage.path, usage.token, usage.body, "revoked-new-use")
            assertThat(revoked.status).isEqualTo(403)
            assertThat(numericAudit(original)).isEqualTo(before)
        }
        assertThat(request("PUT", "/api/users/${original.technician.second}/access", original.stock.token,
            """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        val current = original.copy(technician = replacement)
        numericComplete(current, signature)
        saveCommand("/api/work-orders/${current.workOrder}/approve", current.stock.token, "{}", "reassigned-approve")
        assertThat(numericAudit(current)).isEqualTo(before)
        assertThat(numericTotals(current)).isEqualTo(if (includeCable) "917500|82500|0|9|1|1|10|1" else "1000000|0|0|9|1|1|10|1")
        fixture(current.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement WHERE result='VERIFIED'")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("APPLIED")
        }
    }
}
