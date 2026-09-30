package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReturnITReuse : WarehouseReturnAssetFixture() {
    @Test fun `inspected loan is issued and installed for a new customer with its physical identity and old episode intact`() {
        val returned = recoveredReturn(release = true)
        val old = returned.old
        val receipt = old.installation.receipt
        val stock = receipt.stock
        val admin = stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        val customerResponse = request("POST", "/api/customers", admin,
            """{"code":"REUSE-CUSTOMER","name":"New loan customer","address":"Test address","location":{"longitude":106.9,"latitude":-6.2},"areaId":"${area(admin)}"}""")
        assertThat(customerResponse.status).withFailMessage(customerResponse.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString).path("id").asString()
        val work = workOrder(admin, "PSB", customer)
        assign(admin, work, receipt.receiver.second)
        putPlan(admin, work, plan(admin, work, "[${line(stock.onu, "1", "EA")}]"))
        action(admin, work, "submit-request", command(admin, work, 1))
        action(admin, work, "reserve", command(admin, work, 1))
        val setup = IssueSetup(stock, work, receipt.receiver.second)
        val picked = action(admin, work, "pick", pickBody(setup))
        val issued = action(admin, work, "dispatch", transitionBody(setup, picked))
        val selected = issued.path("lines").single()
        assertThat(selected.path("dimension").path("stockIdentityId").asString()).isEqualTo(asset.toString())
        val input = MaterialReceiptRequest(UUID.fromString(issued.path("issueId").asString()), issued.path("revision").asLong(),
            issued.path("workOrderRevision").asLong(), "new-customer-delivery", listOf(MaterialReceiptSelection(
                UUID.fromString(selected.path("id").asString()), asset, WarehouseBaseUnit.EA, "1", serial = selected.path("serial").asString())))
        val replacement = ReceiptCase(stock, work, receipt.receiver, receipt.transit, receipt.field, input)
        assertThat(acknowledge(replacement, key = "reuse-ack").status).isEqualTo(200)
        val authorization = request("POST", "/api/work-orders/$work/assets/authorize", receipt.receiver.first,
            """{"expectedRevision":${summary(admin, work).path("revisions").path("workOrderRevision").asLong()},"assetId":"$asset","issueLineId":"${selected.path("id").asString()}","purpose":"INSTALL"}""", "reuse-authorize")
        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(200)
        val grant = mapper.readTree(authorization.contentAsString)
        val installation = Installation(replacement, UUID.fromString(customer), UUID.fromString(grant.path("authorizationId").asString()),
            UUID.fromString(grant.path("operationId").asString()))
        val installed = consume(installation, "reuse-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset'")).isEqualTo("2")
            assertThat(scalar("SELECT customer_id FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NULL")).isEqualTo(customer)
            assertThat(scalar("SELECT customer_id FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NOT NULL"))
                .isEqualTo(old.installation.customer.toString())
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='$asset'")).isEqualTo("2")
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner,custody_owner_id) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("CUSTOMER_INSTALLED|ISP|$customer")
        }
    }
}
