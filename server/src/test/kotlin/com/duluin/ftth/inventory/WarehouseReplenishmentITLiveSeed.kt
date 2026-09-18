package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class WarehouseReplenishmentITLiveSeed : WarehouseReplenishmentInboundFixture() {
    @Test fun `prepare legitimate generic posting fixture for packaged HTTP numeric proof`() {
        check(System.getenv("WAREHOUSE_QA") == "true")
        check(System.getenv("SPRING_DATASOURCE_URL") == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val scenario = inbound()
        val identity = mapper.readTree(request("GET", "/api/me", scenario.token).contentAsString)
        val viewer = user(scenario.token, setOf("inventory.request.view"))
        val viewerIdentity = mapper.readTree(request("GET", "/api/me", viewer.first).contentAsString)
        val foreign = prepare()
        val values = mapOf("tenant" to scenario.fixture.tenant.toString(), "adminEmail" to identity.path("email").asString(),
            "viewerEmail" to viewerIdentity.path("email").asString(), "skuId" to scenario.fixture.sku.toString(),
            "locationId" to scenario.target.toString(), "foreignLocationId" to foreign.second.warehouse.toString(),
            "ruleBody" to inboundRule(scenario), "physicalCounts" to scenario.fixture.transaction { counts() })
        assertThat(scenario.fixture.transaction { total(scenario.target) }).isEqualTo(60000)
        val runtime = Path.of(System.getProperty("user.dir")).parent.resolve(".omo/runtime")
        val file = runtime.resolve("task29-live.json")
        check(!Files.isSymbolicLink(runtime) && !Files.isSymbolicLink(file))
        if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, mapper.writeValueAsString(values))
    }
}
