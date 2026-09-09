package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseQueryIT : WarehouseReceiptHttpFixture() {
    @Test fun `stock summary and positions read exact durable cable and devices without mutating ledger`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"REEL","cost":{"totalMinor":"500000","currency":"IDR"}},
            {"skuId":"${setup.onu}","quantityBase":"2","serials":[{"serial":"QUERY-1"},{"serial":"QUERY-2"}]}""")
        transition(setup, draft.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val before = fixture(setup.token).transaction { counts() }
        val response = request("GET", "/api/v1/warehouse/stock", setup.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val result = mapper.readTree(response.contentAsString)
        assertThat(result.path("totalElements").asLong()).isEqualTo(2)
        val cable = result.path("items").single { it.path("skuId").asString() == setup.cable }
        assertThat(cable.path("physical").path("quantityBase").asString()).isEqualTo("1000000")
        assertThat(cable.path("physical").path("baseUnit").asString()).isEqualTo("MM")
        assertThat(cable.path("physical").path("displayQuantity").asString()).isEqualTo("1000.000")
        assertThat(cable.path("available").path("quantityBase").asString()).isEqualTo("0")
        assertThat(cable.path("statusBuckets").path("QUARANTINE").asString()).isEqualTo("1000000")
        val positions = request("GET", "/api/v1/warehouse/stock/positions?skuId=${setup.cable}", setup.token)
        assertThat(positions.status).withFailMessage(positions.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(positions.contentAsString).path("totalElements").asLong()).isEqualTo(1)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
    }

    @Test fun `query rejects malformed filters anonymous and no-view users`() {
        val setup = setupReceipt()
        assertThat(request("GET", "/api/v1/warehouse/stock", null).status).isEqualTo(401)
        val viewer = user(setup.token, setOf("inventory.sku.view"))
        assertThat(request("GET", "/api/v1/warehouse/stock", viewer.first).status).isEqualTo(403)
        for (query in listOf("page=-1", "page=2147483648", "size=0", "size=101", "sort=password", "direction=DESC",
            "skuId=invalid", "condition=UNKNOWN", "owner=OTHER", "status=no", "page=0&page=1", "tenantId=x",
            "from=2026-01-02T00:00:00Z&until=2026-01-01T00:00:00Z", "from=2000-01-01T00:00:00Z&until=2026-01-01T00:00:00Z")) {
            val response = request("GET", "/api/v1/warehouse/stock?$query", setup.token)
            assertThat(response.status).describedAs(query).isEqualTo(400)
        }
    }
}
