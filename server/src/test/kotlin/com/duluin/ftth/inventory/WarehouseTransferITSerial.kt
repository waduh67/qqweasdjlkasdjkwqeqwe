package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseTransferITSerial : WarehouseTransferFixture() {
    @Test fun `serialized transfer preserves physical identity cost title and exactly one position`() {
        val stock = transferStock()
        val setup = stock.setup
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.onu}", setup.token,
            """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false,"expectedRevision":0}""").status).isEqualTo(200)
        val receipt = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"TRANSFER-SERIAL"}],"cost":{"totalMinor":"40000","currency":"IDR"}}""")
        val receiptId = receipt.path("id").asString()
        transition(setup, receiptId, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token).contentAsString).path("lines")[0]
        val identity = line.path("pieces")[0].path("stockIdentityId").asString()
        transition(setup, receiptId, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","quantityBase":"1","baseUnit":"EA"}]}""")
        val transfer = create("transfers", setup.token, transferBody(stock).replace(stock.identity, identity).replace("100000", "1").replace("MM", "EA"))
        val id = transfer.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        val received = transferAction(stock, id, "receive", receiveBody(transfer.path("lines")[0].path("id").asString(), 1, "1").replace("MM", "EA"))
        assertThat(received.path("state").asString()).isEqualTo("RECEIVED")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE id='$identity'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='$identity' AND quantity_base>0")).isEqualTo("1")
            assertThat(scalar("SELECT location_id::text FROM inventory_serialized_asset WHERE id='$identity'")).isEqualTo(stock.destination)
            assertThat(scalar("SELECT cost_total_minor::text||'/'||cost_basis_quantity_base::text FROM inventory_document_line WHERE document_id='$id'")).isEqualTo("40000/1")
        }
    }
}
