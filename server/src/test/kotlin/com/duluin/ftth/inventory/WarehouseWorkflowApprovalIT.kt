package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseWorkflowApprovalIT : WarehouseApprovalHttpFixture() {
    @Test fun `frozen receipt approval finishes after draining without approving new receipts`() {
        val case = pending()
        fixture(case.setup.token).transaction { context.getBean(InventoryTenantPolicyService::class.java).beginDraining(0) }
        val result = decide(case)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("APPROVED")
        counts(case, 1, 1)
        assertThat(request("POST", "/api/v1/warehouse/receipts", case.setup.token,
            draftBody(case.setup, costLine(case.setup))).status).isEqualTo(409)
        counts(case, 1, 1)
    }
}
