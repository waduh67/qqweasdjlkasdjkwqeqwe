package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.reset
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tools.jackson.databind.JsonNode
import java.time.Instant

class WarehouseTransferDiscrepancyRecoveryIT : WarehouseTransferFixture() {
    @MockitoSpyBean lateinit var clock: WarehousePolicyPersistence
    private data class Case(val stock: TransferStock, val target: String, val checker: Pair<String, String>, val report: JsonNode) {
        val id get() = report.path("id").asString()
        val source get() = report.path("resolutionDocumentId").asString()
    }
    private fun reported(): Case {
        val stock = transferStock()
        val target = create("locations", stock.setup.token, """{"code":"LOST","name":"Loss custody","kind":"LOST"}""").path("id").asString()
        val checker = approver(stock.setup.token, listOf(stock.transit, target, stock.setup.bin, stock.destination))
        configure(stock.setup.token, policyBody(listOf(stock.transit, target), listOf(checker.second), "ADJUSTMENT"))
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        transferAction(stock, id, "receive", receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000"))
        return Case(stock, target, checker, transferAction(stock, id, "discrepancy", body(target, 2, "Original")))
    }
    private fun body(target: String, revision: Long, reason: String = "Corrected") =
        """{"expectedRevision":$revision,"action":"LOST","destinationLocationId":"$target","reason":"$reason","evidenceReference":"BA-$reason"}"""
    private fun submit(case: Case, source: String = case.source): String {
        val response = request("POST", "/api/v1/warehouse/approvals/request", case.stock.setup.token,
            """{"sourceDocumentId":"$source","sourceRevision":0}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("requestId").asString()
    }
    private fun recovery(case: Case): JsonNode {
        val response = request("GET", "/api/v1/warehouse/transfers/${case.id}/discrepancy/recovery", case.stock.setup.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        return mapper.readTree(response.contentAsString)
    }
    private fun decision(case: Case, approval: String, action: String) = request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first,
        """{"requestId":"$approval","expectedRevision":0,"decision":"$action","reason":"Verified corrected delivery record"}""")

    @Test fun `rejected report is replaced from actual transfer while immutable approval history and exact stock survive`() {
        val case = reported()
        assertThat(recovery(case).path("canReport").asBoolean()).isTrue()
        val approval = submit(case)
        assertThat(recovery(case).path("block").asString()).isEqualTo("PRIOR_APPROVAL_ACTIVE")
        assertThat(request("POST", "/api/v1/warehouse/transfers/${case.id}/discrepancy", case.stock.setup.token, body(case.target, 3)).status).isEqualTo(409)
        assertThat(request("GET", "/api/v1/warehouse/transfers/${case.id}/discrepancy/recovery", case.checker.first).status).isEqualTo(403)
        val rejected = decision(case, approval, "REJECT")
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        val before = request("GET", "/api/v1/warehouse/approvals/$approval/details", case.stock.setup.token)
        assertThat(mapper.readTree(before.contentAsString).path("actions").path("canRework").asBoolean()).isFalse()
        assertThat(request("POST", "/api/v1/warehouse/approvals/rework", case.stock.setup.token,
            """{"requestId":"$approval","expectedRevision":1}""").status).isEqualTo(409)
        assertThat(recovery(case).path("canReport").asBoolean()).isTrue()
        assertThat(request("POST", "/api/v1/warehouse/transfers/${case.id}/discrepancy", case.stock.setup.token, body(case.target, 2)).status).isEqualTo(409)
        val corrected = transferAction(case.stock, case.id, "discrepancy", body(case.target, 3), "correct-remainder")
        assertThat(corrected.path("revision").asLong()).isEqualTo(4)
        val source = corrected.path("resolutionDocumentId").asString()
        assertThat(source).isNotEqualTo(case.source)
        balances(case.stock, "0", "40000", "60000")
        assertThat(request("GET", "/api/v1/warehouse/approvals/$approval/details", case.stock.setup.token).contentAsString).isEqualTo(before.contentAsString)
        val accepted = decision(case, submit(case, source), "APPROVE")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        assertThat(recovery(case).path("block").asString()).isEqualTo("NO_UNRESOLVED_REMAINDER")
        // Even after the new report posts, a lost response replays its original revision without another report/effect.
        assertThat(transferAction(case.stock, case.id, "discrepancy", body(case.target, 3), "correct-remainder")).isEqualTo(corrected)
        assertThat(request("POST", "/api/v1/warehouse/transfers/${case.id}/discrepancy", case.stock.setup.token, body(case.target, 5)).status).isEqualTo(409)
        fixture(case.stock.setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_approval_effect WHERE source_document_id='$source'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_effect WHERE source_document_id='${case.source}'")).isEqualTo("0")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("60000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='LOST'")).isEqualTo("40000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("100000")
        }
    }

    @Test fun `superseded unsubmitted source cannot request a second approval`() {
        val case = reported()
        val corrected = transferAction(case.stock, case.id, "discrepancy", body(case.target, 3))
        val source = request("GET", "/api/v1/warehouse/approvals/sources/${case.source}", case.stock.setup.token)
        assertThat(source.status).withFailMessage(source.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(source.contentAsString).path("requestBlock").asString()).isEqualTo("SOURCE_NOT_READY")
        assertThat(request("POST", "/api/v1/warehouse/approvals/request", case.stock.setup.token,
            """{"sourceDocumentId":"${case.source}","sourceRevision":0}""").status).isEqualTo(409)
        submit(case, corrected.path("resolutionDocumentId").asString())
        balances(case.stock, "0", "40000", "60000")
    }

    @Test fun `expired report becomes replaceable only after durable termination`() {
        val case = reported()
        doReturn(Instant.now().minusSeconds(48 * 3600)).`when`(clock).now()
        val approval = try { submit(case) } finally { reset(clock) }
        assertThat(recovery(case).path("canReport").asBoolean()).isFalse()
        val expired = request("GET", "/api/v1/warehouse/approvals/$approval/details", case.checker.first)
        assertThat(expired.status).withFailMessage(expired.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(expired.contentAsString).path("approval").path("status").asString()).isEqualTo("EXPIRED")
        assertThat(recovery(case).path("canReport").asBoolean()).isTrue()
        val corrected = transferAction(case.stock, case.id, "discrepancy", body(case.target, 3))
        assertThat(decision(case, submit(case, corrected.path("resolutionDocumentId").asString()), "APPROVE").status).isEqualTo(200)
    }
}
