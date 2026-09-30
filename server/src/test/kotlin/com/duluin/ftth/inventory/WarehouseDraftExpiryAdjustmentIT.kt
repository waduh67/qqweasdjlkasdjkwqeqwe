package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseDraftExpiryAdjustmentIT : WarehouseTransferFixture() {
    @Test fun `due pending discrepancy allows fresh report while old approval cannot move stock`() {
        val stock = transferStock()
        val token = stock.setup.token
        val target = create("locations", token, """{"code":"LOST","name":"Loss custody","kind":"LOST"}""").path("id").asString()
        val checker = approver(token, listOf(stock.transit, target, stock.setup.bin, stock.destination))
        configure(token, policyBody(listOf(stock.transit, target), listOf(checker.second), "ADJUSTMENT"))
        val draft = transfer(stock)
        val parent = draft.path("id").asString()
        transferAction(stock, parent, "dispatch", """{"expectedRevision":0}""")
        transferAction(stock, parent, "receive", receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000"))
        val database = fixture(token)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(5)
        val body = """{"expectedRevision":2,"action":"LOST","destinationLocationId":"$target","reason":"Missing delivery",
            "evidenceReference":"signed-missing-delivery"}"""
        val path = "/api/v1/warehouse/transfers/$parent/discrepancy"
        val original = request("POST", path, token, body, "expiry-adjustment")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(200)
        val id = mapper.readTree(original.contentAsString).path("resolutionDocumentId").asString()
        val approval = request("POST", "/api/v1/warehouse/approvals/request", token,
            """{"sourceDocumentId":"$id","sourceRevision":0}""")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val approvalId = mapper.readTree(approval.contentAsString).path("requestId").asString()
        val before = WarehouseDraftExpiryFacts.capture(database, id)
        clock.awaitDocument(id)
        val recovery = request("GET", "$path/recovery", token)
        assertThat(recovery.status).withFailMessage(recovery.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(recovery.contentAsString).path("canReport").asBoolean()).isTrue()
        database.transaction { assertThat(scalar("SELECT status FROM inventory_approval WHERE id='$approvalId'")).isEqualTo("PENDING") }
        val fresh = request("POST", path, token, body.replace("\"expectedRevision\":2", "\"expectedRevision\":3"), "expiry-fresh")
        assertThat(fresh.status).withFailMessage(fresh.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(fresh.contentAsString).path("resolutionDocumentId").asString()).isNotEqualTo(id)
        val decision = """{"requestId":"$approvalId","expectedRevision":0,"decision":"APPROVE","reason":"Late old report"}"""
        WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "expiry-decision"))
        assertThat(request("POST", path, token, body, "expiry-adjustment").contentAsString).isEqualTo(original.contentAsString)
        WarehouseDraftExpiryFacts.expire(database, id)
        assertThat(WarehouseDraftExpiryFacts.capture(database, id)).isEqualTo(before)
        balances(stock, "0", "40000", "60000")
    }
}
