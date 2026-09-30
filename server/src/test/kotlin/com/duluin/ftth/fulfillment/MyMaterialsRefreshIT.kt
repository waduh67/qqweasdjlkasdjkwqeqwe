package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MyMaterialsRefreshIT : MaterialLifecycleFixture() {
    @Test fun `direct source refresh uses current custody and scope before returning a selected reference`() {
        val case = usageCase()
        val receipt = case.receipt
        val root = "/api/v1/warehouse/my-materials/${receipt.workOrder}"
        val actor = receipt.receiver.first
        val source = "$root/custody/${case.input.lines.single().stockIdentityId}"
        val before = request("GET", source, actor)
        assertThat(before.status).withFailMessage(before.contentAsString).isEqualTo(200)
        assertThat(before.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(request("GET", "$root/issues/${receipt.input.issueId}", actor).status).isEqualTo(200)
        assertThat(request("GET", "$root/issues/${UUID.randomUUID()}", actor).status).isEqualTo(404)
        assertThat(request("GET", "$root/return-locations/${receipt.field}", actor).status).isEqualTo(404)
        assertThat(request("GET", "$source?actorId=${receipt.receiver.second}", actor).status).isEqualTo(400)
        used(case)
        assertThat(request("GET", source, actor).status).isEqualTo(404)
        assign(receipt.stock.token, receipt.workOrder, technician(receipt.stock.token).second)
        assertThat(request("GET", root, actor).status).isEqualTo(200)
        for (location in listOf(receipt.field, receipt.transit, receipt.stock.bin)) assertThat(request("PUT",
            "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/$location", receipt.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", root, actor).status).isEqualTo(404)
        assertThat(request("GET", "$root/issues/${receipt.input.issueId}", actor).status).isEqualTo(404)
    }

    @Test fun `warehouse receiver discovers and acknowledges own scoped pending returns without general work order view`() {
        val case = residualCase()
        val receipt = case.usage.receipt
        val id = dispatchedResidual(case)
        val receiver = user(receipt.stock.token, setOf("inventory.return.view", "inventory.return.manage"))
        val profile = mapper.readTree(request("GET", "/api/me", receiver.first).contentAsString)
        assertThat(request("PUT", "/api/users/${receiver.second}/access", receipt.stock.token,
            mapper.writeValueAsString(mapOf("roleIds" to profile.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(receipt.stock.token))))).status).isEqualTo(200)
        val root = "/api/v1/warehouse/material-returns/pending"
        val hidden = request("GET", root, receiver.first)
        assertThat(hidden.status).withFailMessage(hidden.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(hidden.contentAsString).path("totalElements").asLong()).isZero()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/${case.input.targetLocationId}", receipt.stock.token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val visible = request("GET", "$root?size=1", receiver.first)
        assertThat(visible.status).withFailMessage(visible.contentAsString).isEqualTo(200)
        val row = mapper.readTree(visible.contentAsString).path("items").single()
        assertThat(row.path("id").asString()).isEqualTo(id)
        assertThat(row.path("sku").path("name").asString()).isEqualTo("Cable")
        assertThat(row.path("location").path("name").asString()).isEqualTo("Return intake")
        assertThat(row.path("sender").path("id").asString()).isEqualTo(receipt.receiver.second)
        assertThat(request("GET", root, receipt.receiver.first).status).isEqualTo(403)
        assertThat(request("GET", "$root?receiverId=${receiver.second}", receiver.first).status).isEqualTo(400)
        assertThat(mapper.readTree(request("GET", root, tenant()).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/${case.input.targetLocationId}", receipt.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", root, receiver.first).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/${case.input.targetLocationId}", receipt.stock.token,
            """{"expectedRevision":2,"active":true}""").status).isEqualTo(200)
        val ack = request("POST", "/api/work-orders/${receipt.workOrder}/materials/residuals/acknowledge", receiver.first,
            mapper.writeValueAsString(mapOf("documentId" to id, "expectedRevision" to row.path("revision").asLong(), "evidenceReference" to "warehouse-receiver-signed")), "inbox-ack")
        assertThat(ack.status).withFailMessage(ack.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", root, receiver.first).contentAsString).path("totalElements").asLong()).isZero()
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='QUARANTINE'")).isEqualTo("17500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
        }
    }
}
