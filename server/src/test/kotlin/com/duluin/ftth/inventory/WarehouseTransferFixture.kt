package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

abstract class WarehouseTransferFixture : WarehouseReceiptHttpFixture() {
    protected data class TransferStock(val setup: Setup, val identity: String, val destination: String,
        val transit: String, val receiver: String)

    protected fun transferStock(): TransferStock {
        val setup = setupReceipt()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false,"expectedRevision":0}""").status).isEqualTo(200)
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"100000","lotCode":"TRANSFER","cost":{"totalMinor":"500000","currency":"IDR"}}""")
        val receiptId = receipt.path("id").asString()
        transition(setup, receiptId, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token).contentAsString).path("lines")[0]
        val identity = line.path("pieces")[0].path("stockIdentityId").asString()
        transition(setup, receiptId, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","baseUnit":"MM","quantityBase":"100000"}]}""")
        val destination = create("locations", setup.token,
            """{"code":"DEST","name":"Destination","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val transit = create("locations", setup.token,
            """{"code":"IN_TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString()
        val actor = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString()
        return TransferStock(setup, identity, destination, transit, actor)
    }

    protected fun transferBody(stock: TransferStock): String = """{"sourceLocationId":"${stock.setup.bin}",
        "destinationLocationId":"${stock.destination}","transitLocationId":"${stock.transit}","receiverId":"${stock.receiver}",
        "reason":"Warehouse replenishment","lines":[{"stockIdentityId":"${stock.identity}","quantityBase":"100000","baseUnit":"MM"}]}"""

    protected fun transfer(stock: TransferStock): JsonNode = create("transfers", stock.setup.token, transferBody(stock))

    protected fun transferAction(stock: TransferStock, id: String, action: String, body: String,
        key: String = java.util.UUID.randomUUID().toString()): JsonNode {
        val response = request("POST", "/api/v1/warehouse/transfers/$id/$action", stock.setup.token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    protected fun receiveBody(line: String, revision: Long, amount: String): String =
        """{"expectedRevision":$revision,"evidenceReference":"delivery-note","lines":[{"lineId":"$line","quantityBase":"$amount","baseUnit":"MM"}]}"""

    protected fun balances(stock: TransferStock, source: String, transit: String, destination: String) {
        fixture(stock.setup.token).transaction {
            fun total(location: String) = scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE location_id='$location'")
            assertThat(total(stock.setup.bin)).isEqualTo(source)
            assertThat(total(stock.transit)).isEqualTo(transit)
            assertThat(total(stock.destination)).isEqualTo(destination)
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("100000")
            assertThat(scalar("SELECT count(*) FROM (SELECT movement_id FROM inventory_movement_leg GROUP BY movement_id HAVING sum(CASE WHEN direction='IN' THEN quantity_base ELSE -quantity_base END)<>0) bad")).isEqualTo("0")
        }
    }
}
