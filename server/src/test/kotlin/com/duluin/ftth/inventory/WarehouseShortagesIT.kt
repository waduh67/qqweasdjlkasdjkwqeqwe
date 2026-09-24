package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseShortagesIT : WarehouseReplenishmentFixture() {
    @Test fun `shortages include zero-stock SKUs and compare exact available quantity before paging`() {
        val (token, fixture) = prepare()
        fixture.transaction {
            sql("UPDATE inventory_sku SET minimum_quantity_base=50005,revision=revision+1 WHERE id='$sku'")
            sql("UPDATE inventory_sku SET minimum_quantity_base=2,revision=revision+1 WHERE id='$serialSku'")
        }
        receive(fixture, "20.001")
        val first = request("GET", "/api/v1/warehouse/stock/shortages?size=1", token)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store")
        val data = mapper.readTree(first.contentAsString)
        assertThat(data.path("totalElements").asLong()).isEqualTo(2)
        val cable = data.path("items").single()
        assertThat(cable.path("availableBase").asString()).isEqualTo("20001")
        assertThat(cable.path("shortageBase").asString()).isEqualTo("30004")
        assertThat(cable.path("baseUnit").asString()).isEqualTo("MM")
        val second = mapper.readTree(request("GET", "/api/v1/warehouse/stock/shortages?size=1&page=1", token).contentAsString)
        assertThat(second.path("items").single().path("availableBase").asString()).isEqualTo("0")
        assertThat(second.path("items").single().path("baseUnit").asString()).isEqualTo("EA")
        receive(fixture, "40")
        val after = mapper.readTree(request("GET", "/api/v1/warehouse/stock/shortages", token).contentAsString)
        assertThat(after.path("totalElements").asLong()).isEqualTo(1)
        assertThat(first.contentAsString).doesNotContain("cost", "origin", "customer")
    }

    @Test fun `shortages require current item authority and visible locations and reject misleading filters`() {
        val (token, fixture) = prepare()
        fixture.transaction { sql("UPDATE inventory_sku SET minimum_quantity_base=100,revision=revision+1 WHERE id='$sku'") }
        val noScopes = user(token, setOf("inventory.item.view"))
        val page = request("GET", "/api/v1/warehouse/stock/shortages", noScopes.first)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(page.contentAsString).path("totalElements").asLong()).isZero()
        val denied = user(token, setOf("inventory.request.view"))
        assertThat(request("GET", "/api/v1/warehouse/stock/shortages", denied.first).status).isEqualTo(403)
        for (suffix in listOf("?status=AVAILABLE", "?from=2026-01-01T00:00:00Z", "?size=101", "?page=0&page=1", "?locationId=wrong"))
            assertThat(request("GET", "/api/v1/warehouse/stock/shortages$suffix", token).status).describedAs(suffix).isEqualTo(400)
        val foreign = request("GET", "/api/v1/warehouse/stock/shortages", tenant())
        assertThat(foreign.status).isEqualTo(200)
        assertThat(mapper.readTree(foreign.contentAsString).path("totalElements").asLong()).isZero()
    }
}
