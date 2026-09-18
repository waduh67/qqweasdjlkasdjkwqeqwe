package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseTransferITDurability : WarehouseTransferFixture() {
    @Test fun `revocation blocks replay with the original token`() {
        val stock = transferStock()
        val body = transferBody(stock)
        val first = request("POST", "/api/v1/warehouse/transfers", stock.setup.token, body, "original")
        assertThat(first.status).isEqualTo(201)
        assertThat(request("PUT", "/api/users/${stock.receiver}/access", stock.setup.token,
            """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/transfers", stock.setup.token, body, "original").status).isEqualTo(403)
    }

    @Test fun `interruption at posting phases rolls back split ledger operation and outbox before retry`() {
        val stock = transferStock()
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        val body = receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000")
        for (phase in listOf(TestPostingPhase.DOCUMENT, TestPostingPhase.HEADER, TestPostingPhase.LEGS, TestPostingPhase.BALANCES, TestPostingPhase.EVENTS)) {
            PostingJdbcProbe(context, phase) { throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Injected interruption")) }.use { probe ->
                assertThat(request("POST", "/api/v1/warehouse/transfers/$id/receive", stock.setup.token, body, "retry").status).isEqualTo(409)
                assertThat(probe.observations).isPositive()
            }
            balances(stock, "0", "100000", "0")
            fixture(stock.setup.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE document_id='$id'")).isEqualTo("2")
                assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE parent_segment_id IS NOT NULL")).isEqualTo("0")
            }
        }
        transferAction(stock, id, "receive", body, "retry")
        balances(stock, "0", "40000", "60000")
    }

    @Test fun `partial dispatch retires a reel into distinct conserved transit and warehouse pieces`() {
        val stock = transferStock()
        val draft = create("transfers", stock.setup.token, transferBody(stock).replace("100000", "60000"))
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        balances(stock, "40000", "60000", "0")
        transferAction(stock, id, "receive", receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000"))
        balances(stock, "40000", "0", "60000")
    }
}
