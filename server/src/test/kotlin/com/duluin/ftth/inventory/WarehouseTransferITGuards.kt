package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseTransferITGuards : WarehouseTransferFixture() {
    @ParameterizedTest @ValueSource(strings = ["0", "-1", "1.5", "9223372036854775808"])
    fun `malformed quantities do not create a transfer`(quantity: String) {
        val stock = transferStock()
        val body = transferBody(stock).replace("\"100000\"", "\"$quantity\"")
        assertThat(request("POST", "/api/v1/warehouse/transfers", stock.setup.token, body).status).isEqualTo(400)
        balances(stock, "100000", "0", "0")
    }

    @Test fun `receiver tenant revision and key binding are enforced before effects`() {
        val stock = transferStock()
        val other = approver(stock.setup.token, listOf(stock.setup.bin, stock.destination, stock.transit),
            setOf("inventory.transfer.manage", "inventory.transfer.view"))
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        val path = "/api/v1/warehouse/transfers/$id"
        assertThat(request("POST", "$path/dispatch", stock.setup.token, """{"expectedRevision":1}""").status).isEqualTo(409)
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        val body = receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000")
        assertThat(request("POST", "$path/receive", other.first, body).status).isEqualTo(409)
        assertThat(request("POST", "$path/receive", tenant(), body).status).isEqualTo(404)
        transferAction(stock, id, "receive", body, "bound")
        assertThat(request("POST", "$path/receive", stock.setup.token, body.replace("60000", "40000"), "bound").status).isEqualTo(409)
        assertThat(request("POST", "$path/receive", stock.setup.token, body).status).isEqualTo(409)
        balances(stock, "0", "40000", "60000")
    }

    @Test fun `competing sixty metre receipts cannot spend the same remainder`() {
        val stock = transferStock()
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        val body = receiveBody(draft.path("lines")[0].path("id").asString(), 1, "60000")
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val responses = (1..2).map { pool.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                request("POST", "/api/v1/warehouse/transfers/$id/receive", stock.setup.token, body).status
            }) }.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(responses.sorted()).containsExactly(200, 409)
        } finally { pool.shutdownNow() }
        balances(stock, "0", "40000", "60000")
    }
}
