package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseTransferITDiscrepancy : WarehouseTransferFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOST", "REJECTED"])
    fun `remaining goods require independent approval and never return to available`(action: String) {
        val stock = transferStock()
        val target = create("locations", stock.setup.token,
            """{"code":"EXCEPTION","name":"Exception custody","kind":"${if (action == "LOST") "LOST" else "QUARANTINE"}"}""").path("id").asString()
        val checker = approver(stock.setup.token, listOf(stock.transit, target, stock.setup.bin, stock.destination))
        configure(stock.setup.token, policyBody(listOf(stock.transit, target), listOf(checker.second), "ADJUSTMENT"))
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        transferAction(stock, id, "receive", receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000"))
        val report = transferAction(stock, id, "discrepancy", """{"expectedRevision":2,"action":"$action",
            "destinationLocationId":"$target","reason":"Missing or rejected remainder","evidenceReference":"signed-receipt"}""")
        assertThat(report.path("state").asString()).isEqualTo("DISCREPANCY")
        balances(stock, "0", "40000", "60000")
        val source = report.path("resolutionDocumentId").asString()
        val pending = request("POST", "/api/v1/warehouse/approvals/request", stock.setup.token,
            """{"sourceDocumentId":"$source","sourceRevision":0}""")
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(201)
        val approval = mapper.readTree(pending.contentAsString).path("requestId").asString()
        val body = """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}"""
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", stock.setup.token, body).status).isEqualTo(403)
        val accepted = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, body, "resolve")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, body, "resolve").contentAsString).isEqualTo(accepted.contentAsString)
        fixture(stock.setup.token).transaction {
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE location_id='${stock.transit}'")).isEqualTo("0")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='$target'")).isEqualTo("40000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("60000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("100000")
        }
        if (action == "REJECTED") {
            val remainder = report.path("lines")[0].path("remainingIdentityId").asString()
            val next = create("transfers", stock.setup.token, transferBody(stock)
                .replace(stock.setup.bin, target).replace(stock.identity, remainder).replace("100000", "40000"))
            val nextId = next.path("id").asString()
            transferAction(stock, nextId, "dispatch", """{"expectedRevision":0}""")
            transferAction(stock, nextId, "receive", receiveBody(next.path("lines")[0].path("id").asString(), 1, "40000"))
            fixture(stock.setup.token).transaction {
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("60000")
            }
        }
    }
}
