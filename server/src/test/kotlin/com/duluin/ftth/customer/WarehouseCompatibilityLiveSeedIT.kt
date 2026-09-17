package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class WarehouseCompatibilityLiveSeedIT : WarehouseCompatibilityMaterialFixture() {
    @Test
    fun `prepare real mixed warehouse fixture for built HTTP compatibility proof`() {
        check(System.getenv("WAREHOUSE_QA") == "true")
        check(System.getenv("SPRING_DATASOURCE_URL") == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val installation = mixedMaterials()
        val token = installation.receipt.stock.token
        val legacy = LegacyOnuTestFixture.stage(installation.customer.toString(), "LEGACY-LIVE-COMPATIBILITY")
        val admin = mapper.readTree(request("GET", "/api/me", token).contentAsString)
        val readonly = user(token, setOf("customer.customer.view", "customer.onu.view"))
        val reader = mapper.readTree(request("GET", "/api/me", readonly.first).contentAsString)
        val foreign = mapper.readTree(request("GET", "/api/me", tenant()).contentAsString)
        val portalLogin = "compatibility-${installation.customer}"
        val credential = request("POST", "/api/portal-admin/customers/${installation.customer}/credential", token,
            """{"login":"$portalLogin","password":"local-portal-test-only"}""")
        assertThat(credential.status).withFailMessage(credential.contentAsString).isEqualTo(200)
        val values = mapOf("tenant" to fixture(token).tenant.toString(), "customer" to installation.customer.toString(),
            "asset" to installation.receipt.input.lines.single().stockIdentityId.toString(), "legacyOnu" to legacy,
            "adminEmail" to admin.path("email").asString(), "readerEmail" to reader.path("email").asString(),
            "foreignEmail" to foreign.path("email").asString(), "area" to area(token), "portalLogin" to portalLogin)
        val runtime = Path.of(System.getProperty("user.dir")).parent.resolve(".omo/runtime")
        val manifest = runtime.resolve("task24-live.json")
        check(!Files.isSymbolicLink(runtime) && !Files.isSymbolicLink(manifest))
        if (!Files.exists(manifest)) Files.createFile(manifest, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(manifest, mapper.writeValueAsString(values))
    }
}
