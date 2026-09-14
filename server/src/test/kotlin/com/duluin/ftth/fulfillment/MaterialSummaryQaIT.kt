package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MaterialSummaryQaIT : WarehouseFulfillmentFixture() {
    @ParameterizedTest @ValueSource(strings = ["none", "pending", "rejected", "cancelled", "cancelled-rejected"])
    fun `summary reports the owner QA decision without inventing approval`(state: String) {
        val case = usageCase()
        used(case)
        val receipt = case.receipt
        if (state in setOf("pending", "rejected", "cancelled-rejected")) completeJob(receipt.workOrder, receipt.receiver.first)
        if (state in setOf("rejected", "cancelled-rejected")) {
            assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/reject", receipt.stock.token,
                """{"reason":"Field rework required"}""").status).isEqualTo(200)
        }
        if (state in setOf("cancelled", "cancelled-rejected")) {
            assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/cancel", receipt.stock.token,
                """{"reason":"Cancelled with custody preserved"}""").status).isEqualTo(200)
        }
        val expectedOwner = when (state) {
            "none", "cancelled" -> "NONE"
            "pending" -> "PENDING"
            "rejected", "cancelled-rejected" -> "REJECTED"
            else -> error("Unknown state")
        }
        val before = physicalState(receipt.stock.token)

        val materials = summary(receipt.stock.token, receipt.workOrder)

        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT coalesce(approval_status,'NONE') FROM work_order WHERE id='${receipt.workOrder}'")).isEqualTo(expectedOwner)
        }
        val detail = request("GET", "/api/work-orders/${receipt.workOrder}/materials/settlement", receipt.stock.token)
        assertThat(detail.status).isEqualTo(200)
        val ownerQa = mapper.readTree(detail.contentAsString).path("qaState")
        if (expectedOwner == "NONE") assertThat(ownerQa.isNull).isTrue()
        else assertThat(ownerQa.asString()).isEqualTo(expectedOwner)
        assertThat(materials.path("qaState").asString()).isEqualTo(if (expectedOwner == "NONE") "PENDING" else expectedOwner)
        assertThat(physicalState(receipt.stock.token)).isEqualTo(before)
    }
}
