package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseQueryITPrivacy : WarehouseReceiptHttpFixture() {
    @Test fun `cost revocation uses current authority and inaccessible identifiers have generic responses`() {
        val setup = setupReceipt()
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"PRIVATE","cost":{"totalMinor":"987654321","currency":"IDR"}},
            {"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"PRIVATE-ONU"}],"cost":{"totalMinor":"7654321","currency":"IDR"}}""")
        transition(setup, receipt.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val asset = mapper.readTree(request("GET", "/api/v1/warehouse/assets", setup.token).contentAsString).path("items")[0].path("id").asString()
        val lot = mapper.readTree(request("GET", "/api/v1/warehouse/lots", setup.token).contentAsString).path("items")[0].path("id").asString()
        val (viewer, viewerId) = user(setup.token, setOf("inventory.item.view", "inventory.cost.view"))
        val me = mapper.readTree(request("GET", "/api/me", viewer).contentAsString)
        val role = me.path("roleIds")[0].asString()
        assertThat(request("PUT", "/api/users/$viewerId/access", setup.token,
            """{"roleIds":["$role"],"areaIds":["${area(setup.token)}"]}""").status).isEqualTo(200)
        fixture(setup.token).transaction {
            for (location in listOf(setup.source, setup.inspection)) sql("""INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch)
                VALUES ('${UUID.randomUUID()}','$tenant','$viewerId','$location','$viewerId',0)""")
        }
        assertThat(request("GET", "/api/v1/warehouse/assets/$asset", viewer).contentAsString).contains("7654321")
        val permissions = mapper.readTree(request("GET", "/api/permissions", setup.token).contentAsString)
        val permission = permissions.single { it.path("code").asString() == "inventory.item.view" }.path("id").asString()
        assertThat(request("PUT", "/api/roles/$role", setup.token, """{"name":"No cost","permissionIds":["$permission"]}""").status).isEqualTo(200)
        for (path in listOf("assets", "assets/$asset", "assets/$asset/history", "lots", "lots/$lot", "lots/$lot/segments", "lots/$lot/history", "stock", "stock/positions")) {
            val response = request("GET", "/api/v1/warehouse/$path", viewer)
            assertThat(response.status).describedAs(path).withFailMessage(response.contentAsString).isEqualTo(200)
            assertThat(response.contentAsString).doesNotContain("cost", "totalMinor", "currency", "987654321", "7654321", "supplierId", "externalReference", "evidence", "objectKey")
        }
        val hidden = user(setup.token, setOf("inventory.item.view")).first
        for (token in listOf(hidden, tenant())) {
            val missing = request("GET", "/api/v1/warehouse/assets/${UUID.randomUUID()}", token)
            for (path in listOf("assets/$asset", "assets/$asset/history", "lots/$lot", "lots/$lot/history", "lots/$lot/segments")) {
                val response = request("GET", "/api/v1/warehouse/$path", token)
                assertThat(response.status).isEqualTo(404)
                assertThat(response.contentAsString).isEqualTo(missing.contentAsString)
            }
            assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/stock", token).contentAsString).path("totalElements").asInt()).isZero()
        }
        assertThat(request("POST", "/api/users/$viewerId/disable", setup.token).status).isEqualTo(200)
        assertThat(request("GET", "/api/v1/warehouse/assets/$asset", viewer).status).isEqualTo(403)
    }
}
