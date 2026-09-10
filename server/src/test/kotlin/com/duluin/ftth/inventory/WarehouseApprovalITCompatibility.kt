package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseApprovalITCompatibility : WarehouseApprovalHttpFixture() {
    @Test fun `legacy read projection retains approval identity timestamps and decision field names`() {
        val case = pending()
        val response = request("GET", "/api/inventory/approvals/${case.id}", case.setup.token)
        assertThat(response.status).isEqualTo(200)
        val value = mapper.readTree(response.contentAsString)
        assertThat(value.path("approvalId").asString()).isEqualTo(case.id)
        assertThat(value.path("type").asString()).isEqualTo("RESTOCK")
        assertThat(value.path("amount").asLong()).isEqualTo(101)
        assertThat(value.path("requestedAt").asString()).isNotBlank()
        assertThat(value.path("decisions").isArray).isTrue()
        val queue = mapper.readTree(request("GET", "/api/inventory/approvals/pending", case.checker.first).contentAsString)
        assertThat(queue.path(0).path("approvalId").asString()).isEqualTo(case.id)
        assertThat(queue.path(0).path("amount").isNull).isTrue()
        assertThat(mapper.readTree(request("GET", "/api/inventory/approvals/pending", case.setup.token).contentAsString).size()).isZero()
    }
}
