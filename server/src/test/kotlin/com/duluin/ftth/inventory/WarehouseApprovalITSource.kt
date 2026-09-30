package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseApprovalITSource : WarehouseApprovalHttpFixture() {
    @Test fun `unversioned source line mutation is stale without rewriting original value`() {
        val case = pending()
        fixture(case.setup.token).transaction {
            sql("UPDATE inventory_document_line SET quantity_base=4,revision=revision+1 WHERE document_id='${case.document}'")
        }
        val result = decide(case)
        assertThat(result.status).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("STALE")
        counts(case, 0, 0)
    }
    @Test fun `custodian candidate cannot decide even with current permissions and warehouse scope`() {
        val setup = setupReceipt()
        val custodian = approver(setup.token, listOf(setup.source, setup.inspection))
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(custodian.second, checker.second)))
        val document = draft(setup, costLine(setup)).path("id").asString()
        fixture(setup.token).transaction {
            sql("UPDATE inventory_document_line SET custodian_kind='TECHNICIAN',custodian_id='${custodian.second}',revision=revision+1 WHERE document_id='$document'")
        }
        val case = submit(setup, checker, document)
        assertThat(decide(case, custodian.first).status).isEqualTo(403)
        counts(case, 0, 0)
    }
    @Test fun `source disposition change before final decision is stale and has no effect`() {
        val case = pending()
        fixture(case.setup.token).transaction {
            sql("UPDATE inventory_document SET approval_disposition='REWORK_REQUIRED',revision=revision+1 WHERE id='${case.document}'")
        }
        val result = decide(case)
        assertThat(result.status).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("STALE")
        counts(case, 0, 0)
    }
    @Test fun `one identity cannot provision two independent tiers at request time`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        val tier = """{"userIds":["${checker.second}"],"roleIds":[],"minimumMinor":"%s"}"""
        configure(setup.token, """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${setup.inspection}"],
            "rules":[{"operation":"RECEIPT","tiers":[${tier.format("1")},${tier.format("100")}]}]}""")
        val document = draft(setup, costLine(setup)).path("id").asString()
        val result = request("POST", "/api/v1/warehouse/approvals/request", setup.token, """{"sourceDocumentId":"$document","sourceRevision":0}""")
        assertThat(result.status).isEqualTo(409)
        assertThat(result.contentAsString).contains("INDEPENDENT_APPROVER_REQUIRED")
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_approval")).isEqualTo("0") }
    }
}
