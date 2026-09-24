package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReportPrivacyIT : WarehouseReceiptHttpFixture() {
    @Test fun `revoked cost permission immediately redacts the same historical print and export session`() {
        val stock = setupReceipt()
        val receipt = draft(stock, """{"skuId":"${stock.onu}","quantityBase":"1","serials":[{"serial":"COST-PRIVATE"}],"cost":{"totalMinor":"98989897","currency":"USD"}}""")
        transition(stock, receipt.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val (viewer, userId) = user(stock.token, setOf("inventory.report.view", "inventory.cost.view"))
        val role = mapper.readTree(request("GET", "/api/me", viewer).contentAsString).path("roleIds")[0].asString()
        assertThat(request("PUT", "/api/users/$userId/access", stock.token,
            """{"roleIds":["$role"],"areaIds":["${area(stock.token)}"]}""").status).isEqualTo(200)
        for (location in listOf(stock.source, stock.inspection)) assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/$userId/$location", stock.token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val path = "/api/v1/warehouse/reports/documents/${receipt.path("id").asString()}/revisions/1/print"
        val before = request("GET", path, viewer)
        assertThat(before.status).withFailMessage(before.contentAsString).isEqualTo(200)
        assertThat(before.contentAsString).contains("98989897", "USD")
        val permissions = mapper.readTree(request("GET", "/api/permissions", stock.token).contentAsString)
        val reportPermission = permissions.single { it.path("code").asString() == "inventory.report.view" }.path("id").asString()
        assertThat(request("PUT", "/api/roles/$role", stock.token, """{"name":"Reports only","permissionIds":["$reportPermission"]}""").status).isEqualTo(200)
        for (uri in listOf(path, "/api/v1/warehouse/reports/movements/export.csv", "/api/v1/warehouse/reports/stock")) {
            val after = request("GET", uri, viewer)
            assertThat(after.status).withFailMessage(after.contentAsString).isEqualTo(200)
            assertThat(after.contentAsString).doesNotContain("98989897", "USD", "cost", "currency", "totalMinor", "objectKey", "evidence")
        }
        for (suffix in listOf("", "/export.csv")) assertThat(request("GET", "/api/v1/warehouse/reports/work-order-costs$suffix", viewer).status).isEqualTo(403)
        val foreign = tenant()
        val hidden = request("GET", path, foreign)
        val missing = request("GET", "/api/v1/warehouse/reports/documents/${UUID.randomUUID()}/revisions/1/print", foreign)
        assertThat(hidden.status).isEqualTo(404)
        assertThat(hidden.contentAsString).isEqualTo(missing.contentAsString)
        assertThat(request("POST", "/api/users/$userId/disable", stock.token).status).isEqualTo(200)
        assertThat(request("GET", path, viewer).status).isEqualTo(403)
    }

    @Test fun `legacy unverified raw quantity report never assigns units or available stock`() {
        val stock = setupReceipt()
        val tenantId = fixture(stock.token).tenant
        val balance = UUID.randomUUID()
        // The migration owner stages a historical row; ordinary app-role stock creation is never bypassed.
        context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='$tenantId'")
                statement.execute("""INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,quantity,rebuilt_at,warehouse_admission)
                    VALUES ('$balance','$tenantId','${UUID.randomUUID()}','${stock.cable}','${stock.inspection}','${stock.inspection}','WAREHOUSE','AVAILABLE',37,now(),'LEGACY_UNRESOLVED')""")
            }
            connection.commit()
        }
        val before = fixture(stock.token).transaction { counts() }
        val response = request("GET", "/api/v1/warehouse/reports/unknown-stock", stock.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val row = mapper.readTree(response.contentAsString).path("items").single()
        assertThat(row.path("rawQuantity").asString()).isEqualTo("37")
        assertThat(row.path("baseUnit").isNull).isTrue()
        assertThat(row.path("quantityBase").isNull).isTrue()
        assertThat(row.path("available").asBoolean()).isFalse()
        assertThat(row.path("admission").asString()).isEqualTo("LEGACY_UNRESOLVED")
        for (token in listOf(stock.token, tenant())) {
            val physical = request("GET", "/api/v1/warehouse/reports/stock", token)
            assertThat(physical.status).isEqualTo(200)
            assertThat(mapper.readTree(physical.contentAsString).path("totalElements").asInt()).isZero()
        }
        assertThat(fixture(stock.token).transaction { counts() }).isEqualTo(before)
    }
}
