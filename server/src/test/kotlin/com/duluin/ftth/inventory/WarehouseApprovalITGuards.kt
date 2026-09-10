package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseApprovalITGuards : WarehouseApprovalHttpFixture() {
    @Test fun `policy replacement preserves request exact value and original requirements`() {
        val case = pending()
        val before = fixture(case.setup.token).transaction { scalar("SELECT evaluation_snapshot FROM inventory_approval") }
        configure(case.setup.token, policyBody(listOf(case.setup.inspection), listOf(case.checker.second), threshold = "99999", revision = 1))
        assertThat(decide(case).status).isEqualTo(200)
        fixture(case.setup.token).transaction {
            assertThat(scalar("SELECT evaluation_snapshot FROM inventory_approval")).isEqualTo(before)
            assertThat(scalar("SELECT value_numerator::text||'/'||value_denominator::text||' '||currency FROM inventory_approval")).isEqualTo("101/1 IDR")
            assertThat(scalar("SELECT policy_version FROM inventory_approval")).isEqualTo("1")
        }
        counts(case, 1, 1)
    }
    @Test fun `requester cannot decide and current scope revocation denies queued candidate`() {
        val case = pending()
        assertThat(decide(case, case.setup.token).status).isEqualTo(403)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.checker.second}/${case.setup.inspection}", case.setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(decide(case).status).isEqualTo(404)
        assertThat(request("GET", "/api/v1/warehouse/approvals/${case.id}", case.checker.first).status).isEqualTo(404)
        counts(case, 0, 0)
    }
    @Test fun `disabled checker cannot decide or replay a committed decision`() {
        val case = pending()
        val key = java.util.UUID.randomUUID().toString()
        assertThat(decide(case, key = key).status).isEqualTo(200)
        assertThat(request("POST", "/api/users/${case.checker.second}/disable", case.setup.token).status).isEqualTo(200)
        assertThat(decide(case, key = key).status).isEqualTo(403)
        counts(case, 1, 1)
    }
    @Test fun `source revision mutation creates durable stale response with no effect`() {
        val case = pending()
        val update = draftBody(case.setup, costLine(case.setup, "202")).dropLast(1) + ",\"expectedRevision\":0}"
        assertThat(request("PUT", "/api/v1/warehouse/receipts/${case.document}", case.setup.token, update).status).isEqualTo(200)
        val key = java.util.UUID.randomUUID().toString()
        val stale = decide(case, key = key)
        assertThat(stale.status).withFailMessage(stale.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(stale.contentAsString).path("status").asString()).isEqualTo("STALE")
        assertThat(decide(case, key = key).contentAsString).isEqualTo(stale.contentAsString)
        counts(case, 0, 0)
        fixture(case.setup.token).transaction { assertThat(scalar("SELECT value_numerator FROM inventory_approval")).isEqualTo("101") }
    }
    @Test fun `reject marks source for rework and resubmission requires new document revision`() {
        val case = pending()
        val rejected = request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first, decision(case.id, action = "REJECT", reason = "Correct delivery evidence"))
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(rejected.contentAsString).path("status").asString()).isEqualTo("REWORK_REQUIRED")
        counts(case, 1, 0)
        fixture(case.setup.token).transaction { assertThat(scalar("SELECT approval_disposition FROM inventory_document WHERE id='${case.document}'")).isEqualTo("REWORK_REQUIRED") }
        assertThat(request("POST", "/api/v1/warehouse/receipts/${case.document}/receive", case.setup.token, """{"expectedRevision":1}""").status).isEqualTo(409)
        val body = """{"requestId":"${case.id}","expectedRevision":1}"""
        assertThat(request("POST", "/api/v1/warehouse/approvals/rework", case.checker.first, body).status).isEqualTo(403)
        val key = java.util.UUID.randomUUID().toString()
        val rework = request("POST", "/api/v1/warehouse/approvals/rework", case.setup.token, body, key)
        assertThat(rework.status).withFailMessage(rework.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/rework", case.setup.token, body, key).contentAsString).isEqualTo(rework.contentAsString)
        val next = submit(case.setup, case.checker, case.document, 2)
        assertThat(next.id).isNotEqualTo(case.id)
        assertThat(decide(next).status).isEqualTo(200)
        counts(case, 2, 1)
    }
    @Test fun `authority fields and malformed decision payloads are rejected before effects`() {
        val case = pending()
        for (field in listOf("movementId", "policySnapshotHash", "tier", "approverId", "effectTarget", "amount", "currency")) {
            val result = request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first,
                decision(case.id).dropLast(1) + ",\"$field\":\"bad\"}")
            assertThat(result.status).withFailMessage("$field: ${result.contentAsString}").isEqualTo(400)
        }
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first, decision(case.id, action = "REJECT")).status).isEqualTo(400)
        assertThat(request("GET", "/api/v1/warehouse/approvals?size=101", case.checker.first).status).isEqualTo(400)
        assertThat(request("GET", "/api/v1/warehouse/approvals/${case.id}", null).status).isEqualTo(401)
        counts(case, 0, 0)
    }
}
