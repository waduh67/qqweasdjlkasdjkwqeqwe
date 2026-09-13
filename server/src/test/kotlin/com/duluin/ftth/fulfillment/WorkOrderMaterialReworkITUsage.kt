package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialReworkITUsage : MaterialReworkFixture() {
    @Test fun `remaining custody use binds the new plan while the first fact stays on its original revision`() {
        val case = reworkCase()
        val planned = rework(case)
        assertThat(planned.status).withFailMessage(planned.contentAsString).isEqualTo(200)
        val reworkId = mapper.readTree(planned.contentAsString).path("reworkId").asString()
        val prior = mapper.readTree(case.originalUsage)
        val input = mapper.writeValueAsString(mapOf("expectedRevision" to 1, "workOrderRevision" to case.input.path("workOrderRevision").asLong(),
            "previousUsageId" to prior.path("usageId").asString(), "receiptId" to case.usage.input.lines.single().receiptId,
            "issueLineId" to case.usage.input.lines.single().issueLineId, "stockIdentityId" to prior.path("lines")[0].path("remainder").path("stockIdentityId").asString(),
            "quantityBase" to "7500", "baseUnit" to "MM", "reason" to "Measured rework", "evidenceReference" to "rework-measurement",
            "reworkId" to reworkId, "evidenceRevision" to case.input.path("evidenceRevision").asString()))

        val result = request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/correct-use", case.usage.receipt.receiver.first, input, "rework-use")

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(result.contentAsString).path("planRevision").asLong()).isEqualTo(2)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("90000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED'")).isEqualTo("10000")
            assertThat(scalar("SELECT frozen_snapshot FROM inventory_usage_snapshot WHERE use_revision=1")).isEqualTo(case.originalUsage)
        }
    }

    @Test fun `new positive demand issues and consumes only its delta on the new plan revision`() {
        useAdditionalReceipt(reworkCase())
    }

    @Test fun `resubmission verifies cumulative inherited and new use without another debit`() {
        val case = reworkCase()
        val newest = useAdditionalReceipt(case)
        val original = mapper.readTree(case.originalUsage)
        val line = case.usage.input.lines.single()
        val input = mapper.writeValueAsString(mapOf("expectedRevision" to 2, "workOrderRevision" to case.input.path("workOrderRevision").asLong(),
            "previousUsageId" to newest.path("usageId").asString(), "receiptId" to line.receiptId, "issueLineId" to line.issueLineId,
            "stockIdentityId" to original.path("lines")[0].path("remainder").path("stockIdentityId").asString(), "quantityBase" to "7500",
            "baseUnit" to "MM", "reason" to "Finish inherited material work", "evidenceReference" to "last-measurement",
            "reworkId" to newest.path("planId").asString(), "evidenceRevision" to case.input.path("evidenceRevision").asString()))
        val used = request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/correct-use", case.usage.receipt.receiver.first, input, "last-inherited-use")
        assertThat(used.status).withFailMessage(used.contentAsString).isEqualTo(200)
        resubmit(case)
        val before = physicalState(case.usage.receipt.stock.token)

        val approved = request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/approve", case.usage.receipt.stock.token, "{}")

        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(physicalState(case.usage.receipt.stock.token)).isEqualTo(before)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_customer_material_fact")).isEqualTo("95000")
            assertThat(scalar("SELECT frozen_snapshot FROM inventory_usage_snapshot WHERE use_revision=1")).isEqualTo(case.originalUsage)
        }
    }

    private fun useAdditionalReceipt(case: ReworkCase): tools.jackson.databind.JsonNode {
        val receipt = case.usage.receipt
        val planned = rework(case)
        assertThat(planned.status).withFailMessage(planned.contentAsString).isEqualTo(200)
        val reworkId = mapper.readTree(planned.contentAsString).path("reworkId").asString()
        action(receipt.stock.token, receipt.workOrder, "reserve", command(receipt.stock.token, receipt.workOrder, 2))
        val setup = IssueSetup(receipt.stock, receipt.workOrder, receipt.receiver.second)
        val picked = action(receipt.stock.token, receipt.workOrder, "pick", pickBody(setup))
        val issued = action(receipt.stock.token, receipt.workOrder, "dispatch", transitionBody(setup, picked))
        val issueLine = issued.path("lines")[0]
        val ack = request("POST", "/api/work-orders/${receipt.workOrder}/materials/acknowledge", receipt.receiver.first,
            mapper.writeValueAsString(mapOf("issueId" to issued.path("issueId").asString(), "expectedRevision" to issued.path("revision").asLong(),
                "workOrderRevision" to case.input.path("workOrderRevision").asLong(), "evidenceReference" to "rework-delivery",
                "lines" to listOf(mapOf("issueLineId" to issueLine.path("id").asString(), "stockIdentityId" to issueLine.path("dimension").path("stockIdentityId").asString(),
                    "baseUnit" to "MM", "acceptedBase" to "10000")))), "rework-ack")
        assertThat(ack.status).withFailMessage(ack.contentAsString).isEqualTo(200)
        val accepted = mapper.readTree(ack.contentAsString)
        val input = mapper.writeValueAsString(mapOf("expectedRevision" to 1, "workOrderRevision" to case.input.path("workOrderRevision").asLong(),
            "previousUsageId" to case.input.path("previousUsageId").asString(), "receiptId" to accepted.path("receiptId").asString(),
            "issueLineId" to issueLine.path("id").asString(), "stockIdentityId" to accepted.path("lines")[0].path("accepted").path("stockIdentityId").asString(),
            "quantityBase" to "5000", "baseUnit" to "MM", "reason" to "Measured additional stock", "evidenceReference" to "delta-measurement",
            "reworkId" to reworkId, "evidenceRevision" to case.input.path("evidenceRevision").asString()))

        val result = request("POST", "/api/work-orders/${receipt.workOrder}/materials/correct-use", receipt.receiver.first, input, "new-stock-use")

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(result.contentAsString).path("planRevision").asLong()).isEqualTo(2)
        val totals = summary(receipt.stock.token, receipt.workOrder).path("lines")
        assertThat(totals.sumOf { it.path("requestedBase").asString().toLong() }).isEqualTo(110000)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("87500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED'")).isEqualTo("22500")
            assertThat(scalar("SELECT frozen_snapshot FROM inventory_usage_snapshot WHERE use_revision=1")).isEqualTo(case.originalUsage)
        }
        return mapper.readTree(result.contentAsString)
    }
}
