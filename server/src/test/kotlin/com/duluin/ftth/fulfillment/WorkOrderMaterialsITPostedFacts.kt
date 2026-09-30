package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkOrderMaterialsITPostedFacts : WarehouseIssueFixture() {
    @Test fun `existing posting owner facts produce accountable totals and prohibit plan replacement`() {
        val stock = setupReceipt()
        receiveStock(stock)
        val token = stock.token
        val customerResponse = request("POST", "/api/customers", token,
            """{"code":"FACT-CUSTOMER","name":"Fact customer","areaId":"${area(token)}","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}""")
        assertThat(customerResponse.status).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString).path("id").asString()
        val receiver = technician(token)
        val workOrder = workOrder(token, "PSB", customer)
        assign(token, workOrder, receiver.second)
        putPlan(token, workOrder, plan(token, workOrder, "[${line(stock.cable)}]"))
        action(token, workOrder, "submit-request", command(token, workOrder, 1))
        action(token, workOrder, "reserve", command(token, workOrder, 1))
        val transit = create("locations", token, """{"code":"WO_TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString()
        val field = create("locations", token, """{"code":"FIELD_STOCK","name":"Field custody","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        create("locations", token, """{"code":"CONSUMED","name":"Physical consumption sink","kind":"TRANSIT"}""")
        for (location in listOf(stock.bin, transit, field)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/$location", token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        // Persist the issue and receipt through their owners. Direct document inserts omit
        // the issue-line provenance required by the material-obligation foreign key.
        val setup = IssueSetup(stock, workOrder, receiver.second)
        val picked = action(token, workOrder, "pick", pickBody(setup))
        val dispatched = action(token, workOrder, "dispatch", transitionBody(setup, picked, partial = true))
        val issuedLine = dispatched.path("lines")[0]
        val issueLineId = UUID.fromString(issuedLine.path("id").asString())
        val workOrderRevision = dispatched.path("workOrderRevision").asLong()
        val acknowledgement = MaterialReceiptRequest(UUID.fromString(dispatched.path("issueId").asString()),
            dispatched.path("revision").asLong(), workOrderRevision, "Measured sixty metres",
            listOf(MaterialReceiptSelection(issueLineId, UUID.fromString(issuedLine.path("dimension").path("stockIdentityId").asString()),
                WarehouseBaseUnit.MM, "60000")))
        val received = action(receiver.first, workOrder, "acknowledge", mapper.writeValueAsString(acknowledgement))
        val usage = MaterialUsageRequest(0, 1, workOrderRevision, MaterialMode.MATERIAL_REQUIRED, "Measured forty metres",
            listOf(MaterialUsageSelection(UUID.fromString(received.path("receiptId").asString()), issueLineId,
                UUID.fromString(received.path("lines")[0].path("accepted").path("stockIdentityId").asString()), "40000", WarehouseBaseUnit.MM)))
        action(receiver.first, workOrder, "report-use", mapper.writeValueAsString(usage))
        val result = summary(token, workOrder)
        assertThat(result.path("lines")[0].path("issuedBase").asString()).isEqualTo("60000")
        assertThat(result.path("lines")[0].path("physicallyUsedBase").asString()).isEqualTo("40000")
        assertThat(result.path("lines")[0].path("stillAccountableBase").asString()).isEqualTo("20000")
        assertThat(result.path("lines")[0].path("backorderBase").asString()).isEqualTo("40000")
        assertThat(result.path("revisions").path("useRevision").asLong()).isEqualTo(1)
        val changed = request("PUT", "/api/work-orders/$workOrder/materials/plan", token,
            plan(token, workOrder, "[]", 1, "NONE", "Cannot erase usage"))
        assertThat(changed.status).isEqualTo(409)
        fixture(token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot")).isEqualTo("1") }
    }
}
