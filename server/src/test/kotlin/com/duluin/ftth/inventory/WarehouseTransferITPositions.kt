package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseTransferITPositions : WarehouseTransferFixture() {
    private fun bulk(): TransferStock {
        val stock = transferStock()
        val setup = stock.setup
        val sku = create("skus", setup.token,
            """{"code":"BULK","name":"Bulk units","tracking":"BULK","baseUnit":"EA","inspectionRequired":false}""").path("id").asString()
        val receipt = draft(setup, """{"skuId":"$sku","quantityBase":"100","lotCode":"BULK","cost":{"totalMinor":"10000","currency":"IDR"}}""")
        val id = receipt.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        val identity = line.path("pieces")[0].path("stockIdentityId").asString()
        transition(setup, id, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","baseUnit":"EA","quantityBase":"100"}]}""")
        return stock.copy(identity = identity)
    }

    @ParameterizedTest @ValueSource(strings = ["RECEIVE", "LOST"])
    fun `two bulk transfers at one transit location keep separate document custody`(action: String) {
        val stock = bulk()
        fun draft(quantity: String) = create("transfers", stock.setup.token,
            transferBody(stock).replace("100000", quantity).replace("MM", "EA"))
        val first = draft("30")
        val firstId = first.path("id").asString()
        transferAction(stock, firstId, "dispatch", """{"expectedRevision":0}""")
        val second = draft("20")
        val secondId = second.path("id").asString()
        transferAction(stock, secondId, "dispatch", """{"expectedRevision":0}""")
        val lost = if (action == "LOST") create("locations", stock.setup.token,
            """{"code":"LOST","name":"Lost","kind":"LOST"}""").path("id").asString() else null
        if (lost != null) {
            val checker = approver(stock.setup.token, listOf(stock.transit, lost, stock.setup.bin, stock.destination))
            configure(stock.setup.token, policyBody(listOf(stock.transit, lost), listOf(checker.second), "ADJUSTMENT", "1"))
            val report = transferAction(stock, secondId, "discrepancy", """{"expectedRevision":1,"action":"LOST",
                "destinationLocationId":"$lost","reason":"Missing delivery","evidenceReference":"SIGNED-LOSS"}""")
            val pending = request("POST", "/api/v1/warehouse/approvals/request", stock.setup.token,
                """{"sourceDocumentId":"${report.path("resolutionDocumentId").asString()}","sourceRevision":0}""")
            assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(201)
            val approval = mapper.readTree(pending.contentAsString).path("requestId").asString()
            val decision = request("POST", "/api/v1/warehouse/approvals/decide", checker.first,
                """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""")
            assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(200)
        } else {
            transferAction(stock, firstId, "receive", receiveBody(first.path("lines")[0].path("id").asString(), 1, "10").replace("MM", "EA"))
            transferAction(stock, secondId, "receive", receiveBody(second.path("lines")[0].path("id").asString(), 1, "20").replace("MM", "EA"))
        }
        transferAction(stock, firstId, "receive", receiveBody(first.path("lines")[0].path("id").asString(),
            if (lost == null) 2 else 1, if (lost == null) "20" else "30").replace("MM", "EA"))
        fixture(stock.setup.token).transaction {
            fun total(location: String) = scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE stock_identity_id='${stock.identity}' AND location_id='$location'")
            assertThat(total(stock.setup.bin)).isEqualTo("50")
            assertThat(total(stock.transit)).isEqualTo("0")
            assertThat(total(stock.destination)).isEqualTo(if (lost == null) "50" else "30")
            if (lost != null) assertThat(total(lost)).isEqualTo("20")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE stock_identity_id='${stock.identity}'")).isEqualTo("100")
        }
    }
}
