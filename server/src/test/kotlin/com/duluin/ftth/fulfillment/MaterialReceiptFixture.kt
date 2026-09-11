package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.MaterialReceiptRequest
import com.duluin.ftth.inventory.MaterialReceiptSelection
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseIssueFixture
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

abstract class MaterialReceiptFixture : WarehouseIssueFixture() {
    protected data class ReceiptCase(val stock: Setup, val workOrder: String, val receiver: Pair<String, String>,
        val transit: String, val field: String, val input: MaterialReceiptRequest)

    protected fun receiptCase(serial: Boolean = false): ReceiptCase {
        val stock = setupReceipt()
        if (serial) {
            assertThat(request("PUT", "/api/v1/warehouse/skus/${stock.onu}", stock.token,
                """{"expectedRevision":0,"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false}""").status).isEqualTo(200)
            val receipt = draft(stock, """{"skuId":"${stock.onu}","quantityBase":"2","serials":[{"serial":"RECEIVE-1"},{"serial":"RECEIVE-2"}]}""").path("id").asString()
            transition(stock, receipt, "receive", """{"expectedRevision":0}""")
            val received = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receipt", stock.token).contentAsString)
            val pieces = received.path("lines").asSequence().flatMap { line -> line.path("pieces").asSequence().map { piece ->
                mapOf("lineId" to line.path("id").asString(), "stockIdentityId" to piece.path("stockIdentityId").asString(), "quantityBase" to "1", "baseUnit" to "EA")
            } }.toList()
            transition(stock, receipt, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1, "destinationLocationId" to stock.bin, "lines" to pieces)))
        } else receiveStock(stock, "1000000")
        val receiver = technician(stock.token)
        val workOrder = workOrder(stock.token)
        assign(stock.token, workOrder, receiver.second)
        val planned = if (serial) line(stock.onu, "2", "EA") else line(stock.cable)
        putPlan(stock.token, workOrder, plan(stock.token, workOrder, "[$planned]"))
        action(stock.token, workOrder, "submit-request", command(stock.token, workOrder, 1))
        action(stock.token, workOrder, "reserve", command(stock.token, workOrder, 1))
        val transit = create("locations", stock.token, """{"code":"WO_TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString()
        val field = create("locations", stock.token, """{"code":"FIELD_STOCK","name":"Field custody","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        for (location in listOf(stock.bin, transit, field)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/$location", stock.token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val setup = IssueSetup(stock, workOrder, receiver.second)
        val picked = action(stock.token, workOrder, "pick", pickBody(setup))
        val issue = action(stock.token, workOrder, "dispatch", transitionBody(setup, picked))
        val line = issue.path("lines")[0]
        val input = MaterialReceiptRequest(UUID.fromString(issue.path("issueId").asString()), issue.path("revision").asLong(),
            issue.path("workOrderRevision").asLong(), "signed-paper-handover", listOf(MaterialReceiptSelection(
                UUID.fromString(line.path("id").asString()), UUID.fromString(line.path("dimension").path("stockIdentityId").asString()),
                if (serial) WarehouseBaseUnit.EA else WarehouseBaseUnit.MM, if (serial) "1" else "60000",
                missingBase = if (serial) "0" else "40000", reason = "Remaining delivery pending",
                serial = if (serial) line.path("serial").asString() else null)))
        return ReceiptCase(stock, workOrder, receiver, transit, field, input)
    }

    protected fun acknowledge(case: ReceiptCase, input: MaterialReceiptRequest = case.input, key: String = "acknowledgement") =
        request("POST", "/api/work-orders/${case.workOrder}/materials/acknowledge", case.receiver.first, mapper.writeValueAsString(input), key)

    protected fun received(case: ReceiptCase): String {
        val response = acknowledge(case)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return response.contentAsString
    }

    protected fun accounting(case: ReceiptCase): String = fixture(case.stock.token).transaction {
        scalar("""SELECT concat_ws('|',state,revision,
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='IN_TRANSIT'),
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE custody_owner_kind='TECHNICIAN'),
            (SELECT count(*) FROM inventory_material_receipt), (SELECT count(*) FROM inventory_movement))
            FROM inventory_document WHERE id='${case.input.issueId}'""")
    }
}
