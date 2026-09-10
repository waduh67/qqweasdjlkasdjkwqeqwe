package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseApprovalITRoleAuthority : WarehousePolicyRoleFixture() {
    @Test fun `historical role based request still requires source role permission after replacement`() {
        val scenario = roleScenario()
        grant(scenario.setup.token, scenario.approver.second, listOf(scenario.setup.source))
        val result = request("POST", "/api/v1/warehouse/approvals/request", scenario.setup.token,
            """{"sourceDocumentId":"${scenario.document}","sourceRevision":0}""")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        val id = mapper.readTree(result.contentAsString).path("requestId").asString()
        val body = """{"requestId":"$id","expectedRevision":0,"decision":"APPROVE"}"""
        changeRole(scenario.setup.token, scenario.configuredRole, setOf("inventory.approval.view"))
        val denied = request("POST", "/api/v1/warehouse/approvals/decide", scenario.approver.first, body)
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(403)
        changeRole(scenario.setup.token, scenario.configuredRole, setOf("inventory.approval.view", "inventory.approval.decide"))
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", scenario.approver.first, body)
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
    }
}
