package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

abstract class WarehouseApprovalHttpFixture : WarehousePolicyHttpFixture() {
    protected data class ApprovalCase(val setup: Setup, val checker: Pair<String, String>, val document: String,
        val id: String, val source: String, val requestKey: String, val original: String)
    protected fun pending(): ApprovalCase {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        return submit(setup, checker, document)
    }
    protected fun submit(setup: Setup, checker: Pair<String, String>, document: String, revision: Long = 0): ApprovalCase {
        val source = """{"sourceDocumentId":"$document","sourceRevision":$revision}"""
        val key = UUID.randomUUID().toString()
        val result = request("POST", "/api/v1/warehouse/approvals/request", setup.token, source, key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return ApprovalCase(setup, checker, document, mapper.readTree(result.contentAsString).path("requestId").asString(), source, key, result.contentAsString)
    }
    protected fun decision(id: String, revision: Long = 0, action: String = "APPROVE", reason: String? = null) =
        mapper.writeValueAsString(mapOf("requestId" to id, "expectedRevision" to revision, "decision" to action, "reason" to reason))
    protected fun decide(case: ApprovalCase, actor: String = case.checker.first, revision: Long = 0, key: String = UUID.randomUUID().toString()) =
        request("POST", "/api/v1/warehouse/approvals/decide", actor, decision(case.id, revision), key)
    protected fun counts(case: ApprovalCase, decisions: Int, effects: Int) = fixture(case.setup.token).transaction {
        assertThat(scalar("SELECT count(*) FROM inventory_approval_decision")).isEqualTo(decisions.toString())
        assertThat(scalar("SELECT count(*) FROM inventory_approval_effect")).isEqualTo(effects.toString())
        assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(effects.toString())
        assertThat(scalar("SELECT count(*) FROM inventory_movement_leg")).isEqualTo((effects * 2).toString())
        assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo(effects.toString())
    }
}
