package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseDraftExpiryScopeIT : WarehousePolicyHttpFixture() {
    @Test fun `expiry never restores revoked receipt scope on detail list or original replay`() {
        val setup = setupReceipt()
        val caller = user(setup.token, setOf("inventory.receipt.view", "inventory.receipt.manage", "inventory.cost.view"))
        grant(setup.token, caller.second, listOf(setup.source, setup.inspection))
        val database = fixture(caller.first)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(1)
        val path = "/api/v1/warehouse/receipts"
        val body = """{"supplierId":"${setup.supplier}","externalReference":"EXPIRY-SCOPE",
            "sourceLocationId":"${setup.source}","inspectionLocationId":"${setup.inspection}",
            "lines":[{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"SCOPE-LOT"}]}"""
        val original = request("POST", path, caller.first, body, "scoped-original")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("id").asString()
        clock.awaitDocument(id)
        val visible = request("GET", "$path/$id", caller.first)
        assertThat(visible.status).withFailMessage(visible.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(visible.contentAsString).path("state").asString()).isEqualTo("EXPIRED")
        assertThat(request("POST", path, caller.first, body, "scoped-original").contentAsString).isEqualTo(original.contentAsString)
        val activity = clock.activity(id)
        val revoked = request("PUT", "/api/v1/warehouse/settings/scopes/${caller.second}/${setup.inspection}", setup.token,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoked.status).withFailMessage(revoked.contentAsString).isEqualTo(200)
        fun denied() {
            assertThat(request("GET", "$path/$id", caller.first).status).isEqualTo(404)
            assertThat(request("POST", path, caller.first, body, "scoped-original").status).isEqualTo(404)
            val list = request("GET", "$path?status=EXPIRED", caller.first)
            assertThat(list.status).withFailMessage(list.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(list.contentAsString).path("items").size()).isZero()
            assertThat(mapper.readTree(list.contentAsString).path("totalElements").asLong()).isZero()
        }
        denied()
        WarehouseDraftExpiryFacts.expire(database, id)
        denied()
        assertThat(clock.activity(id)).isEqualTo(activity)
        assertThat(request("GET", "$path/$id", setup.token).status).isEqualTo(200)
    }
}
