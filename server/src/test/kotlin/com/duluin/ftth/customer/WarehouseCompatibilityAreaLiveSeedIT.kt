package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class WarehouseCompatibilityAreaLiveSeedIT : WarehouseCompatibilityMaterialFixture() {
    @Test
    fun `prepare IAM area readers over real mixed material history for built HTTP proof`() {
        check(System.getenv("WAREHOUSE_QA") == "true")
        check(System.getenv("SPRING_DATASOURCE_URL") == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val installation = mixedMaterials()
        val admin = installation.receipt.stock.token
        val otherArea = request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""")
        assertThat(otherArea.status).isEqualTo(201)
        val otherId = mapper.readTree(otherArea.contentAsString).path("id").asString()
        val readers = linkedMapOf<String, String>()
        for ((label, areas) in mapOf("allowed" to listOf(area(admin)), "different" to listOf(otherId), "empty" to emptyList())) {
            val reader = user(admin, setOf("customer.customer.view", "workorder.order.view"))
            val identity = mapper.readTree(request("GET", "/api/me", reader.first).contentAsString)
            val grant = request("PUT", "/api/users/${reader.second}/access", admin,
                mapper.writeValueAsString(mapOf("roleIds" to identity.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to areas)))
            assertThat(grant.status).isEqualTo(200)
            readers["${label}Email"] = identity.path("email").asString()
            readers["${label}Id"] = reader.second
        }
        val noArea = request("POST", "/api/customers", admin,
            """{"name":"No customer area","address":"Test","location":{"longitude":106.8,"latitude":-6.2}}""")
        assertThat(noArea.status).isEqualTo(201)
        val identity = mapper.readTree(request("GET", "/api/me", admin).contentAsString)
        val foreign = mapper.readTree(request("GET", "/api/me", tenant()).contentAsString)
        val portalLogin = "area-${installation.customer}"
        val portal = request("POST", "/api/portal-admin/customers/${installation.customer}/credential", admin,
            """{"login":"$portalLogin","password":"local-portal-test-only"}""")
        assertThat(portal.status).isEqualTo(200)
        val values = readers + mapOf("tenant" to fixture(admin).tenant.toString(), "customer" to installation.customer.toString(),
            "nullAreaCustomer" to mapper.readTree(noArea.contentAsString).path("id").asString(),
            "adminEmail" to identity.path("email").asString(), "foreignEmail" to foreign.path("email").asString(), "portalLogin" to portalLogin)
        val runtime = Path.of(System.getProperty("user.dir")).parent.resolve(".omo/runtime")
        val file = runtime.resolve("task24-area-live.json")
        check(!Files.isSymbolicLink(runtime) && !Files.isSymbolicLink(file))
        if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, mapper.writeValueAsString(values))
    }
}
