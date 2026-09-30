package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehousePolicyITVisibility : WarehousePolicyVisibilityFixture() {
    @Test fun `AV11-02 WH1 ordinary operator gets only actionable status not full policy cost or identities`() {
        val scenario = visibilityScenario()
        val response = evaluate(scenario.operator.first, scenario.document)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val view = mapper.readTree(response.contentAsString)
        assertThat(fields(view)).isEqualTo(statusFields)
        assertThat(view.path("code").asString()).isEqualTo("APPROVAL_REQUIRED")
        assertThat(view.path("requiredAction").asString()).isEqualTo("REQUEST_APPROVAL")
        assertThat(response.contentAsString).doesNotContain(scenario.warehouse2, scenario.approver.second, "IDR", "101")
        assertThat(request("GET", "/api/v1/warehouse/settings/policy", scenario.operator.first).status).isEqualTo(403)
    }
    @Test fun `AV11-02 approval and cost views are separate and same-key replay is freshly redacted after each revocation`() {
        val scenario = visibilityScenario(setOf("inventory.receipt.manage", "inventory.approval.view", "inventory.cost.view"))
        val role = userRoles(scenario.setup.token, scenario.operator.second).single()
        val key = UUID.randomUUID().toString()
        fun replay() = request("POST", "/api/v1/warehouse/settings/evaluate", scenario.operator.first, sourceBody(scenario.document), key)
        val full = mapper.readTree(replay().contentAsString)
        assertThat(fields(full)).isEqualTo(statusFields + setOf("policy", "tiers", "valueNumerator", "valueDenominator", "currency"))
        assertThat(full.path("valueNumerator").asString()).isEqualTo("101")
        assertThat(full.path("policy").path("warehouseIds").asSequence().map { it.asString() }.toList()).containsExactly(scenario.warehouse1)
        assertThat(full.toString()).doesNotContain(scenario.warehouse2, "snapshotHash", "excludedUserIds")
        changeRole(scenario.setup.token, role, setOf("inventory.receipt.manage", "inventory.approval.view"))
        val noCost = mapper.readTree(replay().contentAsString)
        assertThat(fields(noCost)).isEqualTo(statusFields + setOf("policy", "tiers"))
        assertThat(noCost.toString()).doesNotContain("valueNumerator", "valueDenominator", "currency", "minimumMinor", scenario.warehouse2)
        assertThat(noCost.path("tiers").path(0).path("approvers").path(0).path("userId").asString()).isEqualTo(scenario.approver.second)
        changeRole(scenario.setup.token, role, setOf("inventory.receipt.manage", "inventory.cost.view"))
        val status = mapper.readTree(replay().contentAsString)
        assertThat(fields(status)).isEqualTo(statusFields)
        assertThat(status.toString()).doesNotContain(scenario.approver.second, scenario.warehouse2)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${scenario.operator.second}/${scenario.warehouse1}", scenario.setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(replay().status).isEqualTo(404)
    }
    @Test fun `AV11-02 HTTP projection does not alter the complete internal immutable snapshot`() {
        val scenario = visibilityScenario()
        val before = fullEvaluation(scenario.setup.token, scenario.document)
        val projected = evaluate(scenario.operator.first, scenario.document)
        assertThat(projected.status).isEqualTo(200)
        assertThat(fields(mapper.readTree(projected.contentAsString))).isEqualTo(statusFields)
        val after = fullEvaluation(scenario.setup.token, scenario.document)
        assertThat(after).isEqualTo(before)
        assertThat(after.policy?.warehouseIds).containsExactly(UUID.fromString(scenario.warehouse1), UUID.fromString(scenario.warehouse2))
        assertThat(after.valueNumerator).isEqualTo("101")
        assertThat(after.valueDenominator).isEqualTo("1")
        assertThat(after.currency).isEqualTo("IDR")
        assertThat(after.snapshotHash).hasSize(64)
        assertThat(after.excludedUserIds).isNotEmpty()
        assertThat(after.tiers.single().approvers.single().userId).isEqualTo(UUID.fromString(scenario.approver.second))
    }
    @Test fun `AV11-02 setup errors and legacy evaluation also hide currency and private fields`() {
        val scenario = visibilityScenario(setOf("inventory.receipt.manage", "inventory.approval.request"))
        val wrongCurrency = draft(scenario.setup, costLine(scenario.setup, currency = "USD")).path("id").asString()
        val mismatch = evaluate(scenario.operator.first, wrongCurrency)
        assertThat(mismatch.status).isEqualTo(409)
        val body = mapper.readTree(mismatch.contentAsString)
        assertThat(fields(body)).isEqualTo(statusFields)
        assertThat(body.path("code").asString()).isEqualTo("CURRENCY_MISMATCH")
        assertThat(mismatch.contentAsString).doesNotContain("IDR", "USD", scenario.warehouse2, scenario.approver.second)
        val legacy = request("POST", "/api/inventory/approvals", scenario.operator.first, sourceBody(scenario.document))
        assertThat(legacy.status).isEqualTo(403)
        assertThat(legacy.contentAsString).doesNotContain("IDR", "USD", scenario.warehouse2, scenario.approver.second)
    }
}
