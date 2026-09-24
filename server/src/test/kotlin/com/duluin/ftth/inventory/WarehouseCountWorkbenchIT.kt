package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class WarehouseCountWorkbenchIT : WarehouseTransferFixture() {
    private fun read(path: String, token: String): JsonNode {
        val response = request("GET", "/api/v1/warehouse/counts$path", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
    private fun count(stock: TransferStock, entries: List<Pair<String, String>>): JsonNode = create("counts", stock.setup.token,
        mapper.writeValueAsString(mapOf("locationId" to stock.setup.bin, "partialLocation" to true, "reason" to "Independent physical count",
            "entries" to entries.map { mapOf("balanceId" to it.first, "counterId" to it.second) })))
    private fun action(id: String, name: String, token: String, body: String): JsonNode {
        val response = request("POST", "/api/v1/warehouse/counts/$id/$name", token, body)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
    private fun counter(stock: TransferStock) = approver(stock.setup.token, listOf(stock.setup.bin), setOf("inventory.count.view", "inventory.count.manage"))

    @Test fun `count manager discovers named positions and assignments without book quantity or stock permission`() {
        val stock = transferStock()
        val actor = counter(stock)
        val other = counter(stock)
        val positions = read("/positions?locationId=${stock.setup.bin}&size=1", actor.first)
        assertThat(positions.path("totalElements").asLong()).isEqualTo(1)
        val position = positions.path("items").single()
        val balance = position.path("id").asString()
        assertThat(position.path("item").path("name").asString()).isEqualTo("Cable")
        assertThat(position.path("item").path("lotCode").asString()).isEqualTo("TRANSFER")
        assertThat(position.toString()).doesNotContain("quantity", "Quantity", "available", "reserved", "capacity", "cost", "100000")
        assertThat(request("GET", "/api/v1/warehouse/stock/positions", actor.first).status).isEqualTo(403)
        val counters = read("/locations/${stock.setup.bin}/counters", actor.first)
        assertThat(counters.path("items").any { it.path("id").asString() == actor.second && it.path("name").asString().isNotBlank() }).isTrue()
        assertThat(counters.toString()).doesNotContain("email", "permissions", "roleIds", "password")
        val first = count(stock, listOf(balance to actor.second))
        val hidden = count(stock, listOf(balance to other.second))
        val id = first.path("id").asString()
        val list = read("/workbench?size=1", actor.first)
        assertThat(list.path("totalElements").asLong()).isEqualTo(1)
        assertThat(list.path("items").single().path("count")).isEqualTo(first)
        assertThat(read("/workbench?page=1&size=1", actor.first).path("items").size()).isZero()
        val detail = read("/$id/details", actor.first)
        assertThat(detail.path("references").path("requester").path("name").asString()).isNotBlank()
        assertThat(detail.path("references").path("lines").single().path("item").path("name").asString()).isEqualTo("Cable")
        assertThat(detail.toString()).doesNotContain("quantityBase", "bookQuantity", "capacity", "cost", "expectedQuantity")
        val code = detail.path("references").path("code").asString()
        assertThat(read("/workbench?query=$code&skuId=${stock.setup.cable}&state=DRAFT&locationId=${stock.setup.bin}", actor.first).path("totalElements").asLong()).isEqualTo(1)
        assertThat(read("/workbench?skuId=${stock.setup.onu}", actor.first).path("totalElements").asLong()).isZero()
        assertThat(read("/workbench?serial=NOT-PRESENT", actor.first).path("totalElements").asLong()).isZero()
        val from = java.time.Instant.parse(detail.path("references").path("createdAt").asString())
        assertThat(read("/workbench?from=$from&until=${from.plusSeconds(1)}", actor.first).path("totalElements").asLong()).isEqualTo(1)
        assertThat(request("GET", "/api/v1/warehouse/counts/${hidden.path("id").asString()}/details", actor.first).status).isEqualTo(404)
        assertThat(read("/workbench", tenant()).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/details", tenant()).status).isEqualTo(404)
        val started = action(id, "start", stock.setup.token, """{"expectedRevision":0}""")
        assertThat(read("/$id/details", actor.first).path("count")).isEqualTo(started)
        action(id, "observe", actor.first, """{"expectedRevision":1,"balanceId":"$balance","quantityBase":"100000","reason":"Measured whole reel","documentReference":"SHEET-1"}""")
        val reviewer = approver(stock.setup.token, listOf(stock.setup.bin))
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/review/details", reviewer.first).status).isEqualTo(409)
        action(id, "submit", stock.setup.token, """{"expectedRevision":2}""")
        val review = read("/$id/review/details", reviewer.first)
        assertThat(review.path("review").path("observations").single().path("bookQuantityBase").asString()).isEqualTo("100000")
        assertThat(review.path("references").path("lines").single().path("item").path("name").asString()).isEqualTo("Cable")
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/details", reviewer.first).status).isEqualTo(403)
        for (suffix in listOf("/workbench?size=101", "/workbench?state=BOGUS", "/workbench?page=0&page=1", "/positions", "/positions?locationId=${stock.setup.bin}&state=DRAFT", "/locations/${stock.setup.bin}/counters?serial=X"))
            assertThat(request("GET", "/api/v1/warehouse/counts$suffix", actor.first).status).describedAs(suffix).isEqualTo(400)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/${stock.setup.bin}", stock.setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(read("/workbench", actor.first).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/details", actor.first).status).isEqualTo(404)
        assertThat(request("GET", "/api/v1/warehouse/counts/positions?locationId=${stock.setup.bin}", actor.first).status).isEqualTo(404)
    }

    @Test fun `count history applies assigned counter visibility before total and page without exposing book data`() {
        val stock = transferStock()
        val actor = counter(stock)
        val other = counter(stock)
        val receipt = draft(stock.setup, """{"skuId":"${stock.setup.cable}","quantityBase":"17500","lotCode":"SECOND-REEL"}""")
        val receiptId = receipt.path("id").asString()
        transition(stock.setup, receiptId, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", stock.setup.token).contentAsString).path("lines").single()
        val identity = line.path("pieces").single().path("stockIdentityId").asString()
        transition(stock.setup, receiptId, "putaway", """{"expectedRevision":1,"destinationLocationId":"${stock.setup.bin}","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","quantityBase":"17500","baseUnit":"MM"}]}""")
        val positions = read("/positions?locationId=${stock.setup.bin}", actor.first).path("items")
        val originalBalance = positions.single { it.path("stockIdentityId").asString() == stock.identity }.path("id").asString()
        val otherBalance = positions.single { it.path("stockIdentityId").asString() == identity }.path("id").asString()
        val id = count(stock, listOf(originalBalance to actor.second, otherBalance to other.second)).path("id").asString()
        action(id, "start", stock.setup.token, """{"expectedRevision":0}""")
        action(id, "observe", actor.first, """{"expectedRevision":1,"balanceId":"$originalBalance","quantityBase":"99999","reason":"First measured","documentReference":"SHEET-A"}""")
        action(id, "observe", other.first, """{"expectedRevision":2,"balanceId":"$otherBalance","quantityBase":"17500","reason":"Second measured","documentReference":"SHEET-B"}""")
        val own = read("/$id/history/page?size=1", actor.first)
        assertThat(own.path("totalElements").asLong()).isEqualTo(1)
        assertThat(own.path("items").single().path("fact").path("documentReference").asString()).isEqualTo("SHEET-A")
        assertThat(own.toString()).doesNotContain("bookQuantity", "prior_quantity", "17500", "SHEET-B")
        assertThat(read("/$id/history/page?size=1&page=1", actor.first).path("items").size()).isZero()
        val all = read("/$id/history/page?size=1", stock.setup.token)
        assertThat(all.path("totalElements").asLong()).isEqualTo(2)
        assertThat(all.path("items").single().path("fact").path("documentReference").asString()).isEqualTo("SHEET-B")
        assertThat(read("/$id/history/page?size=1&page=1", stock.setup.token).path("items").single().path("fact").path("documentReference").asString()).isEqualTo("SHEET-A")
        assertThat(read("/$id/history?size=1", stock.setup.token).single().path("documentReference").asString()).isEqualTo("SHEET-A")
        for (suffix in listOf("history?size=101", "history/page?size=101", "history/page?state=DRAFT"))
            assertThat(request("GET", "/api/v1/warehouse/counts/$id/$suffix", actor.first).status).isEqualTo(400)
    }
}
