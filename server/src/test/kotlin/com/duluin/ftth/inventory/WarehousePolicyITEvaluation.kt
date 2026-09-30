package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehousePolicyITEvaluation : WarehousePolicyHttpFixture() {
    @Test fun `ordinary receipt manager can evaluate without approval role or implicit scope`() {
        val setup = setupReceipt()
        val document = draft(setup, costLine(setup)).path("id").asString()
        val manager = user(setup.token, setOf("inventory.receipt.manage"))
        assertThat(evaluate(manager.first, document).status).isEqualTo(404)
        grant(setup.token, manager.second, listOf(setup.inspection))
        val result = evaluate(manager.first, document)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(result.contentAsString).contains("IN_POLICY")
        assertThat(request("GET", "/api/v1/warehouse/settings/policy", manager.first).status).isEqualTo(403)
    }
    @Test fun `ordinary receipt requires no approval but threshold exception returns server snapshot without effects`() {
        val setup = setupReceipt()
        val document = draft(setup, costLine(setup)).path("id").asString()
        assertThat(mapper.readTree(evaluate(setup.token, document).contentAsString).path("code").asString()).isEqualTo("IN_POLICY")
        val approver = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(approver.second)))
        val result = evaluate(setup.token, document)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val evaluation = mapper.readTree(result.contentAsString)
        assertThat(evaluation.path("code").asString()).isEqualTo("APPROVAL_REQUIRED")
        assertThat(evaluation.path("valueNumerator").asString()).isEqualTo("101")
        assertThat(evaluation.path("valueDenominator").asString()).isEqualTo("1")
        assertThat(evaluation.path("tiers").path(0).path("approvers").path(0).path("userId").asString()).isEqualTo(approver.second)
        fixture(setup.token).transaction {
            for (table in listOf("inventory_approval", "inventory_approval_decision", "inventory_approval_effect", "inventory_movement"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
        }
    }
    @Test fun `unknown receipt costs block value policy rather than inventing zero`() {
        val setup = setupReceipt()
        val approver = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(approver.second)))
        val document = draft(setup, costLine(setup, null)).path("id").asString()
        val result = evaluate(setup.token, document)
        assertThat(result.status).isEqualTo(409)
        assertThat(result.contentAsString).contains("COST_BASIS_REQUIRED")
    }
    @Test fun `currency mismatch and stale source revisions fail closed`() {
        val setup = setupReceipt()
        val approver = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(approver.second)))
        val document = draft(setup, costLine(setup, currency = "USD")).path("id").asString()
        assertThat(evaluate(setup.token, document).contentAsString).contains("CURRENCY_MISMATCH")
        assertThat(evaluate(setup.token, document, 1).contentAsString).contains("STALE_REVISION")
    }
    @Test fun `warehouse mismatch and current revoked approver block evaluation`() {
        val setup = setupReceipt()
        val approver = approver(setup.token, listOf(setup.inspection, setup.bin))
        val document = draft(setup, costLine(setup)).path("id").asString()
        configure(setup.token, policyBody(listOf(setup.bin), listOf(approver.second)))
        assertThat(evaluate(setup.token, document).status).isEqualTo(404)
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(approver.second), revision = 1))
        assertThat(request("POST", "/api/users/${approver.second}/disable", setup.token).status).isEqualTo(200)
        assertThat(evaluate(setup.token, document).contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
    }
    @Test fun `policy rejects self only missing approvers and malformed threshold rules`() {
        val setup = setupReceipt()
        val me = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString()
        val self = request("PUT", "/api/v1/warehouse/settings/policy", setup.token, policyBody(listOf(setup.inspection), listOf(me)))
        assertThat(self.status).isEqualTo(409)
        assertThat(self.contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
        for (threshold in listOf("0", "-1", "1.5", "01"))
            assertThat(request("PUT", "/api/v1/warehouse/settings/policy", setup.token,
                policyBody(listOf(setup.inspection), listOf(me), threshold = threshold)).status).isEqualTo(400)
        val body = policyBody(listOf(setup.inspection), listOf(me))
        for (field in listOf("value", "amount", "currencyOverride", "actorId", "tenantId", "tiers", "hash", "movementId", "effectTarget")) {
            assertThat(request("POST", "/api/v1/warehouse/settings/evaluate", setup.token,
                """{"sourceDocumentId":"${java.util.UUID.randomUUID()}","sourceRevision":0,"$field":"bad"}""").status).isEqualTo(400)
        }
        assertThat(request("PUT", "/api/v1/warehouse/settings/policy", setup.token, body + "{}").status).isEqualTo(400)
    }
}
