package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaterialWorkbenchReviewIT : WarehouseFulfillmentFixture() {
    @Test fun `approved material review binds the frozen usage and never posts a second debit`() {
        val case = usageCase()
        val recorded = mapper.readTree(used(case))
        val root = "/api/work-orders/${case.receipt.workOrder}/materials/workbench"
        val beforeApproval = request("GET", "$root/approval-review", case.receipt.stock.token)
        assertThat(beforeApproval.status).withFailMessage(beforeApproval.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(beforeApproval.contentAsString).path("review").isNull).isTrue()
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        assertThat(request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}").status).isEqualTo(200)
        val beforeRead = physicalState(case.receipt.stock.token)
        val response = request("GET", "$root/approval-review", case.receipt.stock.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val review = mapper.readTree(response.contentAsString).path("review")
        assertThat(review.path("usage").path("id").asString()).isEqualTo(recorded.path("usageId").asString())
        assertThat(review.path("usage").path("lines").single().path("quantityBase").asString()).isEqualTo("82500")
        assertThat(review.path("usage").path("lines").single().path("sku").path("name").asString()).isEqualTo("Cable")
        assertThat(review.path("workOrderRevision").asLong()).isGreaterThan(case.input.workOrderRevision)
        assertThat(response.contentAsString).doesNotContain("cost", "payload", "sessionId", "subscription", "proofHash")
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(beforeRead)
        assertThat(request("GET", "$root/approval-review", tenant()).status).isEqualTo(404)
        assertThat(request("GET", "$root/approval-review?revision=1", case.receipt.stock.token).status).isEqualTo(400)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receipt.receiver.second}/${case.receipt.field}", case.receipt.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", "$root/approval-review", case.receipt.receiver.first).status).isEqualTo(404)
    }

    @Test fun `obligation names follow authorized issue lines with exact units and server pages`() {
        val case = usageCase()
        used(case)
        val root = "/api/work-orders/${case.receipt.workOrder}/materials/workbench/obligations"
        val response = request("GET", "$root?size=1", case.receipt.receiver.first)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val data = mapper.readTree(response.contentAsString)
        assertThat(data.path("totalElements").asLong()).isEqualTo(1)
        val row = data.path("items").single()
        assertThat(row.path("sku").path("name").asString()).isEqualTo("Cable")
        assertThat(row.path("obligation").path("usedBase").asString()).isEqualTo("82500")
        assertThat(row.path("obligation").path("stillAccountableBase").asString()).isEqualTo("17500")
        assertThat(row.path("id").asString()).isEqualTo(case.input.lines.single().issueLineId.toString())
        assertThat(response.contentAsString).doesNotContain("cost", "email", "receiverId")
        assertThat(mapper.readTree(request("GET", "$root?size=1&page=1", case.receipt.receiver.first).contentAsString).path("items").size()).isZero()
        assertThat(request("GET", root, technician(case.receipt.stock.token).first).status).isEqualTo(403)
    }
}
