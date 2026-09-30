package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseTransferIT : WarehouseTransferFixture() {
    @Test fun `draft has no stock effects and returns original creation on retry`() {
        val stock = transferStock()
        val body = transferBody(stock)
        val first = request("POST", "/api/v1/warehouse/transfers", stock.setup.token, body, "draft")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/v1/warehouse/transfers", stock.setup.token, body, "draft").contentAsString).isEqualTo(first.contentAsString)
        balances(stock, "100000", "0", "0")
    }

    @Test fun `dispatch hundred metres and partial receipt sixty leaves forty in transit with immutable replay`() {
        val stock = transferStock()
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        val line = draft.path("lines")[0].path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""", "dispatch")
        balances(stock, "0", "100000", "0")
        val body = receiveBody(line, 1, "60000")
        val partial = transferAction(stock, id, "receive", body, "partial")
        assertThat(partial.path("state").asString()).isEqualTo("PART_RECEIVED")
        balances(stock, "0", "40000", "60000")
        assertThat(transferAction(stock, id, "receive", body, "partial")).isEqualTo(partial)
        val finished = transferAction(stock, id, "receive", receiveBody(line, 2, "40000"))
        assertThat(finished.path("state").asString()).isEqualTo("RECEIVED")
        assertThat(transferAction(stock, id, "receive", body, "partial")).isEqualTo(partial)
        balances(stock, "0", "0", "100000")
    }

    @Test fun `overreceipt and cancellation after dispatch leave all goods in transit`() {
        val stock = transferStock()
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        val excess = receiveBody(draft.path("lines")[0].path("id").asString(), 1, "101000")
        assertThat(request("POST", "/api/v1/warehouse/transfers/$id/receive", stock.setup.token, excess).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/transfers/$id/cancel", stock.setup.token, """{"expectedRevision":1}""").status).isEqualTo(409)
        balances(stock, "0", "100000", "0")
    }
}
