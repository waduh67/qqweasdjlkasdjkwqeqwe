package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseRmaWorkOrderReadIT : WarehouseRepairFixture() {
    @Test fun `return manager reads current RMA revision and active assigned receivers without material access`() {
        val setup = repairSetup()
        val inspected = inspectRepair(setup, receiveRepair(setup, dispatchRepair(setup)))
        val receipt = setup.returned.old.installation.receipt
        val customer = setup.returned.old.installation.customer
        val work = workOrder(setup.token, "REPAIR", customer.toString())
        assign(setup.token, work, receipt.receiver.second)
        val revision = summary(setup.token, work).path("revisions").path("workOrderRevision").asLong()
        val actor = user(setup.token, setOf("inventory.return.manage"))
        val principal = mapper.readTree(request("GET", "/api/users/${actor.second}", setup.token).contentAsString)
        assertThat(request("PUT", "/api/users/${actor.second}/access", setup.token, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.token))))).status).isEqualTo(200)
        for (location in listOf(setup.returned.quarantine, setup.location))
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/$location", setup.token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val path = "${setup.path}/rma-work-orders/$work"
        val response = request("GET", path, actor.first)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val order = mapper.readTree(response.contentAsString)
        assertThat(order.path("revision").asLong()).isEqualTo(revision)
        assertThat(order.path("id").asString()).isEqualTo(work)
        assertThat(order.path("code").asString()).isNotBlank()
        assertThat(order.path("customerId").asString()).isEqualTo(customer.toString())
        assertThat(order.path("technicians").single().path("id").asString()).isEqualTo(receipt.receiver.second)
        assertThat(order.path("technicians").single().path("name").asString()).isNotBlank()
        assertThat(request("GET", "/api/work-orders/$work/materials", actor.first).status).isEqualTo(403)
        assertThat(request("GET", path, user(setup.token, setOf("inventory.return.view")).first).status).isEqualTo(403)
        assertThat(request("GET", path, tenant()).status).isEqualTo(404)
        val draft = workOrder(setup.token, "REPAIR", customer.toString())
        assertThat(request("GET", "${setup.path}/rma-work-orders/$draft", setup.token).status).isEqualTo(409)
        val wrongType = workOrder(setup.token, "DISMANTLE", customer.toString())
        assign(setup.token, wrongType, receipt.receiver.second)
        assertThat(request("GET", "${setup.path}/rma-work-orders/$wrongType", setup.token).status).isEqualTo(409)
        val unlinked = workOrder(setup.token, "REPAIR")
        assign(setup.token, unlinked, receipt.receiver.second)
        assertThat(request("GET", "${setup.path}/rma-work-orders/$unlinked", setup.token).status).isEqualTo(409)
        assertThat(request("POST", "/api/users/${receipt.receiver.second}/disable", setup.token).status).isEqualTo(200)
        val disabled = request("GET", path, actor.first)
        assertThat(disabled.status).isEqualTo(200)
        assertThat(mapper.readTree(disabled.contentAsString).path("technicians").size()).isZero()
        assertThat(request("POST", "/api/users/${receipt.receiver.second}/enable", setup.token).status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/${setup.location}", setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", path, actor.first).status).isEqualTo(404)
        val finalReturn = request("GET", setup.path, setup.token)
        assertThat(mapper.readTree(finalReturn.contentAsString)).isEqualTo(inspected)
    }
}
