package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialReworkIT : MaterialReworkFixture() {
    @Test fun `authorized caller can obtain exact rework preconditions without database access`() {
        val case = reworkCase()

        val response = request("GET", "/api/work-orders/${case.usage.receipt.workOrder}/materials/rework-context", case.usage.receipt.stock.token)

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val basis = mapper.readTree(response.contentAsString)
        for (field in listOf("expectedRevision", "workOrderRevision", "previousPlanId", "previousUsageId", "expectedUsageRevision", "previousEvidenceRevision", "evidenceRevision")) {
            assertThat(basis.path(field)).describedAs(field).isEqualTo(case.input.path(field))
        }
    }

    @Test fun `ordinary replacement remains rejected after used material and QA rejection`() {
        val case = reworkCase()
        val receipt = case.usage.receipt
        val before = usageAccounting(case.usage)

        val response = request("PUT", "/api/work-orders/${receipt.workOrder}/materials/plan", receipt.stock.token,
            plan(receipt.stock.token, receipt.workOrder, "[${line(receipt.stock.cable, "110000")}]", expected = 1), "ordinary-replace")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        assertThat(summary(receipt.stock.token, receipt.workOrder).path("revisions").path("planRevision").asLong()).isEqualTo(1)
    }

    @Test fun `explicit rejected rework appends plan and evidence lineage without changing physical history`() {
        val case = reworkCase()
        val before = usageAccounting(case.usage)

        val response = rework(case)

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val body = mapper.readTree(response.contentAsString)
        assertThat(body.path("plan").path("planRevision").asLong()).isEqualTo(2)
        assertThat(body.path("previousPlan")).isEqualTo(case.previousPlan)
        assertThat(body.path("previousEvidenceRevision").asString()).isEqualTo(case.input.path("previousEvidenceRevision").asString())
        assertThat(body.path("evidenceRevision").asString()).isEqualTo(case.input.path("evidenceRevision").asString())
        assertThat(usageAccounting(case.usage)).isEqualTo(before)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_plan")).isEqualTo("2")
            assertThat(scalar("SELECT frozen_snapshot FROM inventory_usage_snapshot WHERE use_revision=1")).isEqualTo(case.originalUsage)
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_customer_material_fact")).isEqualTo("82500")
        }
    }
}
