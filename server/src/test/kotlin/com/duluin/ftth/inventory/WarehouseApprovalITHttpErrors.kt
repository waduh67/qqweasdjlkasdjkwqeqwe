package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseApprovalITHttpErrors : WarehousePolicyHttpFixture() {
    @Test fun `missing decision permission returns a structured forbidden error before source lookup`() {
        val setup = setupReceipt()
        val viewer = approver(setup.token, listOf(setup.bin), setOf("inventory.approval.view"))
        val response = request("POST", "/api/v1/warehouse/approvals/decide", viewer.first,
            """{"requestId":"${UUID.randomUUID()}","expectedRevision":0,"decision":"APPROVE"}""")
        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentType).isEqualTo("application/json")
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("FORBIDDEN")
    }
}
