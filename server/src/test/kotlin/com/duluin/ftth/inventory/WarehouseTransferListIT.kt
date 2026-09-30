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
            assertThat(request("GET", "/api/v1/warehouse/transfers/$firstId/details", viewer.first).status).isEqualTo(404)
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
        assertThat(visible.path("totalElements").asLong()).isEqualTo(2)
        assertThat(list(admin, "size=1&page=1").path("items").size()).isEqualTo(1)
        val repairable = details(admin, firstId)
        assertThat(repairable.path("transfer")).isEqualTo(first)
        assertThat(repairable.path("references").path("people").single { it.path("id").asString() == receiver.second }
            .path("active").asBoolean()).isFalse()
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
        assertThat(request("POST", "/api/users/${receiver.second}/disable", admin).status).isEqualTo(200)
        assertThat(list(admin).path("totalElements").asLong()).isEqualTo(1)
        assertThat(list(admin).path("items").single().path("transfer").path("id")).isEqualTo(second.path("id"))
        assertThat(request("GET", "/api/v1/warehouse/transfers/$firstId/details", admin).status).isEqualTo(409)
        for (invalid in listOf("page=-1", "size=0", "size=101", "page=1.5", "page=2147483648", "state=BOGUS",
            "state=", "state=DRAFT&state=RECEIVED", "page=0&page=1", "query=", "query=" + "a".repeat(201), "locationId=1-1-1-1-1", "extra=true",
            "skuId=1-1-1-1-1", "serial=", "serial=" + "A".repeat(201), "serial=A&serial=B", "from=2026-01-01", "until=2026-01-02T00:00:00Z",
            "from=2026-01-01T00:00:00Z&until=2026-01-01T00:00:00Z", "from=2025-01-01T00:00:00Z&until=2026-09-01T00:00:00Z"))
            assertThat(request("GET", "/api/v1/warehouse/transfers?$invalid", admin).status).describedAs(invalid).isEqualTo(400)
    }

    @Test fun `SKU and exact serial match the same line with creation dates and scopes before pagination`() {
        val stock = transferStock()
        val setup = stock.setup
        val cableOnly = transfer(stock)
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.onu}", setup.token,
            """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false,"expectedRevision":0}""").status).isEqualTo(200)
        val receipt = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"FILTER-SERIAL"}]}""")
        val receiptId = receipt.path("id").asString()
        transition(setup, receiptId, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token).contentAsString).path("lines")[0]
        val identity = line.path("pieces")[0].path("stockIdentityId").asString()
        transition(setup, receiptId, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","quantityBase":"1","baseUnit":"EA"}]}""")
        val body = transferBody(stock).replace("}]}", """},{"stockIdentityId":"$identity","quantityBase":"1","baseUnit":"EA"}]}""")
        val mixed = create("transfers", setup.token, body)
        val mixedId = mixed.path("id").asString()
        assertThat(list(setup.token, "skuId=${setup.cable}&size=1").path("totalElements").asLong()).isEqualTo(2)
        val serialResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/warehouse/transfers")
            .param("skuId", setup.onu).param("serial", " filter-serial ").param("size", "1")
            .header("Authorization", "Bearer ${setup.token}")).andReturn().response
        assertThat(serialResponse.status).withFailMessage(serialResponse.contentAsString).isEqualTo(200)
        val selected = mapper.readTree(serialResponse.contentAsString)
        assertThat(selected.path("totalElements").asLong()).isEqualTo(1)
        assertThat(selected.path("items").single().path("transfer").path("id").asString()).isEqualTo(mixedId)
        assertThat(list(setup.token, "skuId=${setup.cable}&serial=FILTER-SERIAL").path("totalElements").asLong()).isZero()
        assertThat(list(setup.token, "serial=FILTER").path("totalElements").asLong()).isZero()
        assertThat(list(setup.token, "serial=FILTER-SERIAL&size=1&page=1").path("items").size()).isZero()
        val micros = fixture(setup.token).transaction {
            scalar("SELECT (extract(epoch FROM created_at)*1000000)::bigint FROM inventory_document WHERE id='$mixedId'").toLong()
        }
        val createdAt = java.time.Instant.ofEpochSecond(micros / 1000000, (micros % 1000000) * 1000)
        assertThat(list(setup.token, "serial=FILTER-SERIAL&from=$createdAt&until=${createdAt.plusSeconds(1)}").path("totalElements").asLong()).isEqualTo(1)
        assertThat(list(setup.token, "serial=FILTER-SERIAL&from=${createdAt.minusSeconds(1)}&until=$createdAt").path("totalElements").asLong()).isZero()
        val viewer = approver(setup.token, listOf(setup.bin, stock.destination), setOf("inventory.transfer.view"))
        assertThat(list(viewer.first, "serial=FILTER-SERIAL&size=1").path("totalElements").asLong()).isZero()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${viewer.second}/${stock.transit}", setup.token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(list(viewer.first, "serial=FILTER-SERIAL&locationId=${stock.transit}&state=DRAFT&size=1").path("totalElements").asLong()).isEqualTo(1)
        assertThat(details(setup.token, cableOnly.path("id").asString()).path("transfer")).isEqualTo(cableOnly)
        assertThat(details(setup.token, mixedId).path("transfer")).isEqualTo(mixed)
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
        fun history(query: String): JsonNode {
            val response = request("GET", "/api/v1/warehouse/transfers/$id/history/page?$query", viewer.first)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            return mapper.readTree(response.contentAsString)
        }
        val latest = history("page=0&size=2")
        assertThat(latest.path("totalElements").asLong()).isEqualTo(3)
        assertThat(latest.path("items").asSequence().map { it.path("revision").asLong() }.toList()).containsExactly(2L,1L)
        assertThat(history("page=1&size=2").path("items").single()).isEqualTo(draft)
        assertThat(history("page=2&size=2").path("items").size()).isZero()
        val oldPage = request("GET", "/api/v1/warehouse/transfers/$id/history?page=1&size=1", viewer.first)
        assertThat(oldPage.status).isEqualTo(200)
        assertThat(mapper.readTree(oldPage.contentAsString).single().path("revision").asLong()).isEqualTo(1)
        for (suffix in listOf("/history", "/history/page")) {
            for (invalid in listOf("size=101", "page=-1", "size=0", "page=1&page=2", "state=DRAFT", "size="))
                assertThat(request("GET", "/api/v1/warehouse/transfers/$id$suffix?$invalid", viewer.first).status).isEqualTo(400)
        }
        val target = create("locations", admin, """{"code":"LOST","name":"Lost goods","kind":"LOST"}""").path("id").asString()
        transferAction(stock, id, "discrepancy", """{"expectedRevision":2,"action":"LOST","destinationLocationId":"$target",
            "reason":"Missing remainder","evidenceReference":"signed-receipt"}""")
        assertThat(list(admin, "locationId=$target&state=DISCREPANCY").path("totalElements").asLong()).isEqualTo(1)
        assertThat(list(viewer.first).path("totalElements").asLong()).isZero()
        for (suffix in listOf("", "/details", "/history", "/history/page"))
            assertThat(request("GET", "/api/v1/warehouse/transfers/$id$suffix", viewer.first).status).isEqualTo(404)
        val scope = "/api/v1/warehouse/settings/scopes/${viewer.second}/$target"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(list(viewer.first).path("totalElements").asLong()).isEqualTo(1)
        assertThat(details(viewer.first, id).path("transfer").path("resolutionDocumentId").asString()).isNotBlank()
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(list(viewer.first).path("totalElements").asLong()).isZero()
        balances(stock, "0", "40000", "60000")
    }
}
