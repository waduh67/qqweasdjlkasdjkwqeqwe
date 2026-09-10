package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehousePolicyITRoleAuthority : WarehousePolicyRoleFixture() {
    @Test fun `AV11-01 configured role permission and membership govern direct and delegated paths despite another deciding role`() {
        val scenario = roleScenario()
        val admin = scenario.setup.token
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", admin, delegationBody(scenario))
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        assertThat(candidates(admin, scenario.document)).containsExactlyInAnyOrder(scenario.approver.second, scenario.delegate.second)
        changeRole(admin, scenario.configuredRole, setOf("inventory.approval.view"))
        val revoked = evaluate(admin, scenario.document)
        assertThat(revoked.status).withFailMessage(revoked.contentAsString).isEqualTo(409)
        assertThat(revoked.contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED").doesNotContain(scenario.approver.second, scenario.delegate.second)
        changeRole(admin, scenario.configuredRole, setOf("inventory.approval.view", "inventory.approval.decide"))
        assertThat(candidates(admin, scenario.document)).containsExactlyInAnyOrder(scenario.approver.second, scenario.delegate.second)
        assignRoles(admin, scenario.approver.second, listOf(scenario.otherRole))
        assertThat(evaluate(admin, scenario.document).contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
        assignRoles(admin, scenario.approver.second, listOf(scenario.configuredRole, scenario.otherRole))
        assertThat(candidates(admin, scenario.document)).containsExactlyInAnyOrder(scenario.approver.second, scenario.delegate.second)
    }
    @Test fun `AV11-01 delegation creation rejects source role without its own current deciding permission`() {
        val scenario = roleScenario()
        changeRole(scenario.setup.token, scenario.configuredRole, setOf("inventory.approval.view"))
        val response = request("POST", "/api/v1/warehouse/settings/delegations", scenario.setup.token, delegationBody(scenario))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(response.contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
        fixture(scenario.setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_approval_delegation")).isEqualTo("0") }
    }
    @Test fun `AV11-01 explicit user survives source role revocation but cannot lend authority to that role delegation`() {
        val scenario = roleScenario()
        val admin = scenario.setup.token
        configure(admin, rolePolicy(scenario.setup.inspection, scenario.configuredRole, listOf(scenario.approver.second), 1))
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations", admin, delegationBody(scenario)).status).isEqualTo(200)
        changeRole(admin, scenario.configuredRole, setOf("inventory.approval.view"))
        assertThat(candidates(admin, scenario.document)).containsExactly(scenario.approver.second)
        changeRole(admin, scenario.otherRole, emptySet())
        assertThat(evaluate(admin, scenario.document).contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
        changeRole(admin, scenario.otherRole, setOf("inventory.approval.decide"))
        assertThat(candidates(admin, scenario.document)).containsExactly(scenario.approver.second)
    }
}
