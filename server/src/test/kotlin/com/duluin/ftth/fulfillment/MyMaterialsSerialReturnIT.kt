package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.MaterialResidualRequest
import com.duluin.ftth.inventory.WarehouseBaseUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MyMaterialsSerialReturnIT : MaterialReceiptFixture() {
    @Test fun `acknowledged unused serial returns from former assignee to warehouse quarantine without customer installation`() {
        val case = receiptCase(serial = true)
        received(case)
        assign(case.stock.token, case.workOrder, technician(case.stock.token).second)
        val root = "/api/v1/warehouse/my-materials/${case.workOrder}"
        val own = request("GET", "$root/custody", case.receiver.first)
        assertThat(own.status).withFailMessage(own.contentAsString).isEqualTo(200)
        val source = mapper.readTree(own.contentAsString).path("items").single()
        assertThat(source.path("serial").asString()).isEqualTo("RECEIVE-1")
        assertThat(source.path("quantityBase").asString()).isEqualTo("1")
        val target = create("locations", case.stock.token, """{"code":"UNUSED_SERIAL","name":"Unused serial quarantine","kind":"QUARANTINE"}""").path("id").asString()
        val context = mapper.readTree(request("GET", root, case.receiver.first).contentAsString)
        val input = MaterialResidualRequest(context.path("workOrderRevision").asLong(), UUID.fromString(source.path("receiptId").asString()),
            UUID.fromString(source.path("issueLineId").asString()), UUID.fromString(source.path("id").asString()), "1", WarehouseBaseUnit.EA,
            UUID.fromString(target), "Unused ONU", "Scanned RECEIVE-1 and signed return")
        val endpoint = "/api/work-orders/${case.workOrder}/materials/return"
        val sent = request("POST", endpoint, case.receiver.first, mapper.writeValueAsString(input), "serial-return")
        assertThat(sent.status).withFailMessage(sent.contentAsString).isEqualTo(200)
        val residualId = mapper.readTree(sent.contentAsString).path("id").asString()
        assertThat(request("POST", endpoint, case.receiver.first, mapper.writeValueAsString(input), "serial-return").contentAsString).isEqualTo(sent.contentAsString)
        assertThat(mapper.readTree(request("GET", "$root/custody", case.receiver.first).contentAsString).path("items").isEmpty).isTrue()
        val history = mapper.readTree(request("GET", "$root/residuals", case.receiver.first).contentAsString).path("items").single()
        assertThat(history.path("serial").asString()).isEqualTo("RECEIVE-1")
        val ack = request("POST", "/api/work-orders/${case.workOrder}/materials/residuals/acknowledge", case.stock.token,
            mapper.writeValueAsString(mapOf("documentId" to residualId, "expectedRevision" to history.path("revision").asLong(), "evidenceReference" to "Warehouse scanned RECEIVE-1")), "serial-return-ack")
        assertThat(ack.status).withFailMessage(ack.contentAsString).isEqualTo(200)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${input.stockIdentityId}' AND status='QUARANTINE' AND quantity_base=1")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${input.stockIdentityId}'")).isEqualTo("0")
        }
    }
}
