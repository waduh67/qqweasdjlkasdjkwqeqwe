package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseApprovalIT : WarehousePolicyHttpFixture() {
    @Test fun `durable request final decision and receipt posting replay original responses`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        val source = """{"sourceDocumentId":"$document","sourceRevision":0}"""
        val requestKey = UUID.randomUUID().toString()
        val pending = request("POST", "/api/v1/warehouse/approvals/request", setup.token, source, requestKey)
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(201)
        val id = mapper.readTree(pending.contentAsString).path("requestId").asString()
        val decision = """{"requestId":"$id","expectedRevision":0,"decision":"APPROVE"}"""
        val key = UUID.randomUUID().toString()
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, key)
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(approved.contentAsString).path("status").asString()).isEqualTo("APPROVED")
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, key).contentAsString).isEqualTo(approved.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/approvals/request", setup.token, source, requestKey).contentAsString).isEqualTo(pending.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", setup.token, decision, key).status).isEqualTo(403)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_approval")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_decision")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_effect")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement_leg")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$document'")).isEqualTo("RECEIVED_IN_INSPECTION")
        }
    }

    @Test fun `policy gated receipt cannot bypass approval by ordinary receive`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        val result = request("POST", "/api/v1/warehouse/receipts/$document/receive", setup.token, """{"expectedRevision":0}""")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("code").asString()).isEqualTo("APPROVAL_REQUIRED")
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0") }
    }
}
