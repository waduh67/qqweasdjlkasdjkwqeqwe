package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialLifecycleITDelta : MaterialUsageFixture() {
    @Test fun `additional measured use appends a delta without rewriting consumed history`() {
        val case = usageCase()
        val original = used(case)
        val snapshot = mapper.readTree(original)
        val source = case.input.lines.single()
        val body = mapper.writeValueAsString(mapOf("expectedRevision" to 1, "workOrderRevision" to case.input.workOrderRevision,
            "previousUsageId" to snapshot.path("usageId").asString(), "receiptId" to source.receiptId, "issueLineId" to source.issueLineId,
            "stockIdentityId" to snapshot.path("lines")[0].path("remainder").path("stockIdentityId").asString(),
            "quantityBase" to "7500", "baseUnit" to "MM", "evidenceReference" to "additional-measurement", "reason" to "Additional field work"))

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/correct-use", case.receipt.receiver.first, body, "use-delta")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("90000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED'")).isEqualTo("10000")
            assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot")).isEqualTo("2")
            assertThat(scalar("SELECT frozen_snapshot FROM inventory_usage_snapshot WHERE use_revision=1")).isEqualTo(original)
        }
    }
}
