package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.util.UUID

class WarehouseMasterIT : WarehouseMasterHttpFixture() {

    @Test fun `legacy reads retain arrays and permission boundaries without setup writes`() {
        val token = tenant()
        for (path in listOf("warehouses", "items", "stock", "reservations", "custody")) {
            assertThat(mvc.perform(get("/api/inventory/$path")).andReturn().response.status).isEqualTo(401)
            val response = mvc.perform(get("/api/inventory/$path").header("Authorization", "Bearer $token")).andReturn().response
            assertThat(response.status).isEqualTo(200)
            assertThat(mapper.readTree(response.contentAsString).isArray).isTrue()
        }
        assertThat(mvc.perform(post("/api/inventory/warehouses").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response.status).isEqualTo(405)
    }

    @Test fun `empty tenant creates supplier through durable HTTP command and replays immutable response`() {
        val token = tenant()
        val key = UUID.randomUUID().toString()
        fun create(name: String) = mvc.perform(post("/api/v1/warehouse/suppliers")
            .header("Authorization", "Bearer $token").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content("""{"code":"SUP-1","name":"$name","contactReference":"sales@example.test"}"""))
            .andReturn().response
        val first = create("Supplier")
        assertThat(first.status).isEqualTo(201)
        val replay = create("Supplier")
        assertThat(replay.status).isEqualTo(201)
        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        assertThat(create("Changed").status).isEqualTo(409)
    }

    @Test fun `HTTP only setup creates hierarchy SKU and supplier with revision updates and archives`() {
        val token = tenant()
        val warehouse = create("locations", token, """{"code":"WH-1","name":"Main warehouse","kind":"WAREHOUSE","issueEligible":true}""")
        val warehouseId = warehouse.path("id").asString()
        val bin = create("locations", token, """{"code":"BIN-1","name":"Rack 1","kind":"BIN","parentLocationId":"$warehouseId","issueEligible":true}""")
        val me = mapper.readTree(request("GET", "/api/me", token).contentAsString)
        create("locations", token, """{"code":"TECH-1","name":"Technician","kind":"TECHNICIAN","custodianId":"${me.path("id").asString()}"}""")
        create("locations", token, """{"code":"VAN-1","name":"Van","kind":"VEHICLE"}""")
        val sku = create("skus", token, """{"code":"ONU-1","name":"Optical terminal","category":"ONT","model":"Model A","tracking":"SERIAL","baseUnit":"EA","minimumQuantityBase":"10"}""")
        val path = "/api/v1/warehouse/skus/${sku.path("id").asString()}"
        val update = """{"code":"ONU-1","name":"Updated terminal","tracking":"SERIAL","baseUnit":"EA","expectedRevision":0}"""
        val key = UUID.randomUUID().toString()
        val changed = request("PUT", path, token, update, key)
        assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(changed.contentAsString).path("revision").asLong()).isEqualTo(1)
        assertThat(request("PUT", path, token, update, key).contentAsString).isEqualTo(changed.contentAsString)
        assertThat(request("PUT", path, token, update).status).isEqualTo(409)
        assertThat(request("POST", "$path/archive", token, """{"expectedRevision":1}""").status).isEqualTo(200)
        assertThat(request("PUT", path, token, update.replace(":0", ":2")).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/locations/$warehouseId/archive", token, """{"expectedRevision":0}""").status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/locations/${bin.path("id").asString()}/archive", token, """{"expectedRevision":0}""").status).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/locations/$warehouseId/archive", token, """{"expectedRevision":0}""").status).isEqualTo(200)
        assertThat(request("GET", path, token).status).isEqualTo(200)
    }

    @Test fun `bounded stable pagination filters code name search without crossing tenant`() {
        val token = tenant()
        for (code in listOf("SKU-C", "SKU-A", "SKU-B")) create("skus", token, """{"code":"$code","name":"Cable","tracking":"LOT","baseUnit":"MM"}""")
        fun page(query: String) = mapper.readTree(request("GET", "/api/v1/warehouse/skus?$query", token).contentAsString)
        assertThat(page("size=2").path("totalElements").asLong()).isEqualTo(3)
        assertThat(page("size=2").path("items").asSequence().map { it.path("code").asString() }.toList()).containsExactly("SKU-A", "SKU-B")
        assertThat(page("size=2&page=1").path("items").asSequence().map { it.path("code").asString() }.toList()).containsExactly("SKU-C")
        assertThat(page("code=SKU-A&name=Cable&search=SKU").path("totalElements").asLong()).isEqualTo(1)
        assertThat(page("sort=name").toString()).isEqualTo(page("sort=name").toString())
        for (query in listOf("size=0", "size=101", "page=-1", "sort=tenant_id", "direction=invalid"))
            assertThat(request("GET", "/api/v1/warehouse/skus?$query", token).status).isEqualTo(400)
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/skus", tenant()).contentAsString).path("totalElements").asLong()).isZero()
    }
}
