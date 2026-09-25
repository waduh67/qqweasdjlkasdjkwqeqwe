package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class WarehouseLegacyReadScopeIT : WarehouseIssueFixture() {
    private val paths = listOf("warehouses", "items", "stock", "reservations", "custody")
    private val permissions = setOf("inventory.location.view", "inventory.item.view", "inventory.custody.view")

    @Test fun `every retained reader applies warehouse and area scopes and revocation to the existing session`() {
        val setup = issuedSetup(serials = 1)
        val stock = setup.stock
        val admin = stock.token
        val visibleAsset = read(admin, "items").single().path("id").asString()
        val secondAreaResponse = request("POST", "/api/areas", admin, """{"code":"SECOND","name":"Second area"}""")
        assertThat(secondAreaResponse.status).isEqualTo(201)
        val secondArea = mapper.readTree(secondAreaResponse.contentAsString).path("id").asString()
        val adminId = mapper.readTree(request("GET", "/api/me", admin).contentAsString).path("id").asString()
        setAreas(admin, adminId, listOf(area(admin), secondArea))
        val hiddenWarehouse = create("locations", admin,
            """{"code":"SECOND","name":"Second warehouse","kind":"WAREHOUSE","areaId":"$secondArea"}""")
            .path("id").asString()
        val hiddenLocation = create("locations", admin,
            """{"code":"SECOND_BIN","name":"Second bin","kind":"BIN","parentLocationId":"$hiddenWarehouse","areaId":"$secondArea","issueEligible":true}""")
            .path("id").asString()
        val receipt = draft(stock, """{"skuId":"${stock.onu}","quantityBase":"1","serials":[{"serial":"PRIVATE-OTHER-AREA"}]}""")
        val receiptId = receipt.path("id").asString()
        transition(stock, receiptId, "receive", """{"expectedRevision":0}""")
        val received = request("GET", "/api/v1/warehouse/receipts/$receiptId", admin)
        assertThat(received.status).isEqualTo(200)
        val sourceLine = mapper.readTree(received.contentAsString).path("lines").single()
        val hiddenAsset = sourceLine.path("pieces").single().path("stockIdentityId").asString()
        transition(stock, receiptId, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "destinationLocationId" to hiddenLocation, "lines" to listOf(mapOf("lineId" to sourceLine.path("id").asString(),
                "stockIdentityId" to hiddenAsset, "baseUnit" to "EA", "quantityBase" to "1")))))
        val viewer = user(admin, permissions)
        setAreas(admin, viewer.second, listOf(area(admin), secondArea))
        val before = fixture(admin).transaction { scalar("SELECT json_build_array((SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),(SELECT count(*) FROM inventory_balance_projection),(SELECT count(*) FROM inventory_reservation))::text") }

        // Correct area alone never grants warehouse visibility.
        assertEmpty(viewer.first)
        scope(admin, viewer.second, stock.bin, 0, true)
        assertVisibleOnly(viewer.first, stock.bin, visibleAsset, hiddenAsset)
        scope(admin, viewer.second, hiddenLocation, 0, true)
        assertThat(read(viewer.first, "items").map { it.path("id").asString() }).containsExactlyInAnyOrder(visibleAsset, hiddenAsset)
        assertThat(request("GET", "/api/inventory/serialized/$hiddenAsset", viewer.first).status).isEqualTo(200)

        // Keep both warehouse grants; removing area B must hide B immediately.
        setAreas(admin, viewer.second, listOf(area(admin)))
        assertVisibleOnly(viewer.first, stock.bin, visibleAsset, hiddenAsset)
        scope(admin, viewer.second, stock.bin, 1, false)
        assertEmpty(viewer.first)
        assertThat(request("GET", "/api/inventory/serialized/$visibleAsset", viewer.first).status).isEqualTo(404)
        scope(admin, viewer.second, stock.bin, 2, true)
        assertVisibleOnly(viewer.first, stock.bin, visibleAsset, hiddenAsset)

        val otherTenant = tenant()
        val missing = request("GET", "/api/inventory/serialized/${UUID.randomUUID()}", otherTenant)
        val foreign = request("GET", "/api/inventory/serialized/$visibleAsset", otherTenant)
        assertThat(foreign.status).isEqualTo(404)
        assertThat(foreign.contentAsString).isEqualTo(missing.contentAsString)
        assertThat(request("POST", "/api/users/${viewer.second}/disable", admin).status).isEqualTo(200)
        for (path in paths + "serialized/$visibleAsset")
            assertThat(request("GET", "/api/inventory/$path", viewer.first).status).describedAs(path).isEqualTo(403)
        assertThat(fixture(admin).transaction { scalar("SELECT json_build_array((SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),(SELECT count(*) FROM inventory_balance_projection),(SELECT count(*) FROM inventory_reservation))::text") }).isEqualTo(before)
    }

    @Test fun `legacy route families keep their own permission without requiring item view for custody or locations`() {
        val stock = setupReceipt()
        val receipt = draft(stock, """{"skuId":"${stock.onu}","quantityBase":"1","serials":[{"serial":"PERMISSION-ONU"}]}""")
        transition(stock, receipt.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val asset = read(stock.token, "items").single().path("id").asString()
        val families = mapOf("inventory.location.view" to setOf("warehouses"),
            "inventory.item.view" to setOf("items", "stock", "serialized/$asset"),
            "inventory.custody.view" to setOf("custody", "reservations"))
        for ((permission, allowed) in families) {
            val viewer = user(stock.token, setOf(permission))
            setAreas(stock.token, viewer.second, listOf(area(stock.token)))
            scope(stock.token, viewer.second, stock.inspection, 0, true)
            for (path in paths + "serialized/$asset") {
                val response = request("GET", "/api/inventory/$path", viewer.first)
                assertThat(response.status).describedAs("$permission $path").isEqualTo(if (path in allowed) 200 else 403)
                assertThat(request("GET", "/api/inventory/$path", null).status).isEqualTo(401)
            }
        }
    }

    private fun assertVisibleOnly(token: String, location: String, asset: String, hidden: String) {
        assertThat(read(token, "warehouses").map { it.path("id").asString() }).containsExactly(location)
        assertThat(read(token, "items").map { it.path("id").asString() }).containsExactly(asset)
        assertThat(read(token, "stock").map { it.path("locationId").asString() }).containsExactly(location)
        for (path in listOf("custody", "reservations"))
            assertThat(read(token, path).map { it.path("assetId").asString() }).containsExactly(asset)
        val serialized = read(token, "serialized/$asset")
        assertThat(serialized.propertyNames()).containsExactlyInAnyOrder("assetId", "tenantId", "skuId", "serialNumber",
            "macAddress", "status", "locationId", "custodyOwnerId", "installedOnuId")
        assertThat(serialized.path("assetId").asString()).isEqualTo(asset)
        val missing = request("GET", "/api/inventory/serialized/${UUID.randomUUID()}", token)
        val inaccessible = request("GET", "/api/inventory/serialized/$hidden", token)
        assertThat(inaccessible.status).isEqualTo(404)
        assertThat(inaccessible.contentAsString).isEqualTo(missing.contentAsString)
        for (path in paths) assertThat(read(token, path).toString())
            .doesNotContain(hidden, "PRIVATE-OTHER-AREA", "cost", "totalMinor", "currency", "evidence")
    }

    private fun assertEmpty(token: String) {
        for (path in paths) assertThat(read(token, path).size()).describedAs(path).isZero()
    }

    private fun read(token: String, path: String): JsonNode {
        val response = request("GET", "/api/inventory/$path", token)
        assertThat(response.status).describedAs(path).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).contains("no-store")
        return mapper.readTree(response.contentAsString)
    }

    private fun setAreas(admin: String, user: String, areas: List<String>) {
        val current = mapper.readTree(request("GET", "/api/users/$user", admin).contentAsString)
        val response = request("PUT", "/api/users/$user/access", admin,
            mapper.writeValueAsString(mapOf("roleIds" to current.path("roleIds").map { it.asString() }, "areaIds" to areas)))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }

    private fun scope(admin: String, user: String, location: String, revision: Long, active: Boolean) {
        val response = request("PUT", "/api/v1/warehouse/settings/scopes/$user/$location", admin,
            """{"expectedRevision":$revision,"active":$active}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
}
