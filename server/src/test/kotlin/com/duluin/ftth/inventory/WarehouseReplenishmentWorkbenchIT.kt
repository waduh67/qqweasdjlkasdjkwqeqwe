package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReplenishmentWorkbenchIT : WarehouseReplenishmentFixture() {
    @Test fun `request-only reader gets named scoped pages and current position without generating a suggestion`() {
        val (token, fixture) = prepare()
        val rule = createRule(token, fixture)
        val id = rule.path("id").asString()
        val reader = user(token, setOf("inventory.request.view"))
        grantReader(token, reader.second, fixture.warehouse.toString())
        val page = request("GET", "$root/workbench/rules?active=true&size=1", reader.first)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        assertThat(page.getHeader("Cache-Control")).isEqualTo("no-store")
        val parsed = mapper.readTree(page.contentAsString)
        assertThat(parsed.path("totalElements").asLong()).isEqualTo(1)
        assertThat(parsed.path("items").single().path("sku").path("name").asString()).isEqualTo("Cable")
        assertThat(parsed.path("items").single().path("location").path("id").asString()).isEqualTo(fixture.warehouse.toString())
        assertThat(request("GET", "/api/v1/warehouse/skus/${fixture.sku}", reader.first).status).isEqualTo(403)
        val details = read(reader.first, "workbench/rules/$id")
        assertThat(details.path("suggestedQuantityBase").asString()).isEqualTo("100000")
        assertThat(details.path("position").path("availableBase").asString()).isEqualTo("0")
        assertThat(details.path("request").isNull).isTrue()
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_replenishment_request") }).isEqualTo("0")
        val suggestion = recompute(token, rule)
        assertThat(read(reader.first, "workbench/requests?state=PENDING").path("totalElements").asLong()).isEqualTo(1)
        receive(fixture, "60")
        val changed = read(reader.first, "workbench/requests/${suggestion.path("id").asString()}")
        assertThat(changed.path("suggestedQuantityBase").asString()).isEqualTo("0")
        assertThat(changed.path("position").path("availableBase").asString()).isEqualTo("60000")
        assertThat(changed.path("request").path("availableBase").asString()).isEqualTo("0")
        assertThat(changed.path("request").path("quantityBase").asString()).isEqualTo("100000")
        assertThat(request("POST", "$root/requests/${suggestion.path("id").asString()}/accept", reader.first, acceptance(suggestion)).status).isEqualTo(403)
        val fulfilled = recompute(token, rule)
        assertThat(fulfilled.path("state").asString()).isEqualTo("FULFILLED")
        assertThat(read(reader.first, "workbench/requests?state=PENDING").path("totalElements").asLong()).isZero()
        assertThat(read(reader.first, "workbench/requests?state=FULFILLED").path("totalElements").asLong()).isEqualTo(1)
        command(token, "rules/$id/archive", """{"expectedRevision":0}""")
        assertThat(read(reader.first, "workbench/rules?active=false").path("totalElements").asLong()).isEqualTo(1)
        assertThat(read(reader.first, "workbench/rules?active=true").path("totalElements").asLong()).isZero()
        assertThat(details.toString()).doesNotContain("cost", "permissions", "areaIds")
    }

    @Test fun `filters fail closed and revoked warehouse scope disappears before counts and details`() {
        val (token, fixture) = prepare()
        val rule = createRule(token, fixture)
        val reader = user(token, setOf("inventory.request.view"))
        grantReader(token, reader.second, fixture.warehouse.toString())
        for (suffix in listOf("?active=other", "?page=0&page=1", "?size=101", "?unknown=x", "?locationId=bad", "?skuId="))
            assertThat(request("GET", "$root/workbench/rules$suffix", reader.first).status).describedAs(suffix).isEqualTo(400)
        assertThat(request("GET", "$root/workbench/requests?state=OTHER", reader.first).status).isEqualTo(400)
        assertThat(request("GET", "$root/workbench/rules?locationId=${fixture.technician}", reader.first).status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${reader.second}/${fixture.warehouse}", token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(read(reader.first, "workbench/rules").path("totalElements").asLong()).isZero()
        assertThat(request("GET", "$root/workbench/rules/${rule.path("id").asString()}", reader.first).status).isEqualTo(404)
        assertThat(read(tenant(), "workbench/rules").path("totalElements").asLong()).isZero()
    }

    private fun grantReader(admin: String, userId: String, location: String) {
        val principal = mapper.readTree(request("GET", "/api/users/$userId", admin).contentAsString)
        val result = request("PUT", "/api/users/$userId/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin)))))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val scope = request("PUT", "/api/v1/warehouse/settings/scopes/$userId/$location", admin, """{"expectedRevision":0,"active":true}""")
        assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
    }
}
