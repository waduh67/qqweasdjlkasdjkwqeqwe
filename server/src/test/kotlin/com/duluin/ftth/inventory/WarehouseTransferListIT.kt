package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class WarehouseTransferListIT : WarehouseTransferFixture() {
    private fun list(token: String, query: String = "page=0&size=25"): JsonNode {
        val response = request("GET", "/api/v1/warehouse/transfers?$query", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    private fun details(token: String, id: String): JsonNode {
        val response = request("GET", "/api/v1/warehouse/transfers/$id/details", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    @Test fun `discovery scopes all locations before counting and paging with named current references`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val first = transfer(stock)
        val firstId = first.path("id").asString()
        val secondDestination = create("locations", admin,
            """{"code":"OTHER_DEST","name":"Other destination","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val second = transfer(stock.copy(destination = secondDestination))
        val secondId = second.path("id").asString()
        val page0 = list(admin, "page=0&size=1")
        assertThat(page0.path("totalElements").asLong()).isEqualTo(2)
        assertThat(page0.path("items").single().path("transfer").path("id").asString()).isEqualTo(secondId)
        assertThat(list(admin, "page=1&size=1").path("items").single().path("transfer").path("id").asString()).isEqualTo(firstId)
        assertThat(list(admin, "page=2&size=1").path("items").size()).isZero()
        assertThat(list(admin, "query=${first.path("code").asString()}").path("items").single().path("transfer")).isEqualTo(first)
        assertThat(list(admin, "state=DISPATCHED").path("totalElements").asLong()).isZero()
        assertThat(list(admin, "locationId=$secondDestination").path("totalElements").asLong()).isEqualTo(1)
        val viewer = approver(admin, listOf(stock.setup.bin,stock.transit,stock.destination), setOf("inventory.transfer.view"))
        val visible = list(viewer.first, "page=0&size=1")
        assertThat(visible.path("totalElements").asLong()).isEqualTo(1)
        assertThat(visible.path("items").single().path("transfer").path("id").asString()).isEqualTo(firstId)
        assertThat(list(viewer.first, "page=1&size=1").path("items").size()).isZero()
        assertThat(list(viewer.first, "locationId=$secondDestination").path("totalElements").asLong()).isZero()
        val named = details(viewer.first, firstId)
        assertThat(named.path("transfer")).isEqualTo(first)
        assertThat(named.path("references").path("lines").single().path("skuName").asString()).isEqualTo("Cable")
        assertThat(named.path("references").path("lines").single().path("lotCode").asString()).isEqualTo("TRANSFER")
        assertThat(named.path("references").path("locations").asSequence().map { it.path("name").asString() }.toList()).contains("Destination", "Transit")
        val me = mapper.readTree(request("GET", "/api/me", admin).contentAsString)
        assertThat(named.path("references").path("people").single().path("name").asString()).isEqualTo(me.path("name").asString())
        assertThat(named.toString()).doesNotContain("totalMinor", "costBasisQuantityBase", "password", "email")
        for (location in listOf(stock.setup.bin,stock.transit,stock.destination)) {
            val scope = "/api/v1/warehouse/settings/scopes/${viewer.second}/$location"
            assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
            assertThat(list(viewer.first, "page=0&size=1").path("totalElements").asLong()).isZero()
            assertThat(request("GET", "/api/v1/warehouse/transfers/$firstId/details", viewer.first).status).isEqualTo(403)
            assertThat(request("PUT", scope, admin, """{"expectedRevision":2,"active":true}""").status).isEqualTo(200)
        }
        assertThat(list(tenant()).path("totalElements").asLong()).isZero()
        val denied = user(admin, setOf("inventory.item.view"))
        assertThat(request("GET", "/api/v1/warehouse/transfers", denied.first).status).isEqualTo(403)
        assertThat(request("GET", "/api/v1/warehouse/transfers/$firstId/details", denied.first).status).isEqualTo(403)
        balances(stock, "100000", "0", "0")
    }

    @Test fun `current labels and inactive receivers cannot rewrite the operation or corrupt page totals`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val receiver = user(admin, setOf("inventory.transfer.view"))
        val first = transfer(stock.copy(receiver = receiver.second))
        val firstId = first.path("id").asString()
        val second = transfer(stock)
        assertThat(list(admin, "size=1").path("totalElements").asLong()).isEqualTo(2)
        assertThat(request("POST", "/api/users/${receiver.second}/disable", admin).status).isEqualTo(200)
        val visible = list(admin, "size=1")
        assertThat(visible.path("totalElements").asLong()).isEqualTo(1)
        assertThat(visible.path("items").single().path("transfer").path("id")).isEqualTo(second.path("id"))
        assertThat(list(admin, "size=1&page=1").path("items").size()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/transfers/$firstId/details", admin).status).isEqualTo(409)
        assertThat(request("POST", "/api/users/${receiver.second}/enable", admin).status).isEqualTo(200)
        assertThat(list(admin).path("totalElements").asLong()).isEqualTo(2)
        assertThat(request("PUT", "/api/v1/warehouse/skus/${stock.setup.cable}", admin,
            """{"code":"CABLE","name":"Renamed cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false,"expectedRevision":1}""").status).isEqualTo(200)
        val renamed = details(admin, firstId)
        assertThat(renamed.path("references").path("lines").single().path("skuName").asString()).isEqualTo("Renamed cable")
        assertThat(renamed.path("transfer")).isEqualTo(first)
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/transfers/$firstId", admin).contentAsString)).isEqualTo(first)
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/transfers/$firstId/history", admin).contentAsString).single()).isEqualTo(first)
        // Cosmetic changes do not participate in the captured stock equality used for dispatch.
        transferAction(stock, firstId, "dispatch", """{"expectedRevision":0}""")
        balances(stock, "0", "100000", "0")
        for (invalid in listOf("page=-1", "size=0", "size=101", "page=1.5", "page=2147483648", "state=BOGUS",
            "state=", "state=DRAFT&state=RECEIVED", "page=0&page=1", "query=", "query=" + "a".repeat(201), "locationId=1-1-1-1-1", "extra=true"))
            assertThat(request("GET", "/api/v1/warehouse/transfers?$invalid", admin).status).describedAs(invalid).isEqualTo(400)
    }

    @Test fun `partial quantities and discrepancy destination stay scoped on discovery detail and history`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val viewer = approver(admin, listOf(stock.setup.bin,stock.transit,stock.destination), setOf("inventory.transfer.view"))
        val draft = transfer(stock)
        val id = draft.path("id").asString()
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        val partial = transferAction(stock, id, "receive", receiveBody(draft.path("lines").single().path("id").asString(), 1, "60000"))
        val visible = list(viewer.first, "state=PART_RECEIVED").path("items").single()
        assertThat(visible.path("transfer")).isEqualTo(partial)
        val line = visible.path("transfer").path("lines").single()
        assertThat(line.path("quantityBase").asString()).isEqualTo("100000")
        assertThat(line.path("receivedBase").asString()).isEqualTo("60000")
        assertThat(line.path("inTransitBase").asString()).isEqualTo("40000")
        assertThat(line.path("resolvedBase").asString()).isEqualTo("0")
        val target = create("locations", admin, """{"code":"LOST","name":"Lost goods","kind":"LOST"}""").path("id").asString()
        transferAction(stock, id, "discrepancy", """{"expectedRevision":2,"action":"LOST","destinationLocationId":"$target",
            "reason":"Missing remainder","evidenceReference":"signed-receipt"}""")
        assertThat(list(admin, "locationId=$target&state=DISCREPANCY").path("totalElements").asLong()).isEqualTo(1)
        assertThat(list(viewer.first).path("totalElements").asLong()).isZero()
        for (suffix in listOf("", "/details", "/history"))
            assertThat(request("GET", "/api/v1/warehouse/transfers/$id$suffix", viewer.first).status).isEqualTo(403)
        val scope = "/api/v1/warehouse/settings/scopes/${viewer.second}/$target"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(list(viewer.first).path("totalElements").asLong()).isEqualTo(1)
        assertThat(details(viewer.first, id).path("transfer").path("resolutionDocumentId").asString()).isNotBlank()
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(list(viewer.first).path("totalElements").asLong()).isZero()
        balances(stock, "0", "40000", "60000")
    }
}
