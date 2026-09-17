package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import java.util.UUID

class WarehouseCompatibilityITAreaScope : WarehouseCompatibilityMaterialFixture() {
    @Test
    fun `unrestricted platform reads and unscoped owner lookup remain available`() {
        val login = request("POST", "/api/auth/login", null,
            """{"tenantSlug":"platform","email":"root@ftth.local","password":"rootadmin123"}""")
        assertThat(login.status).isEqualTo(200)
        val token = mapper.readTree(login.contentAsString).path("accessToken").asString()
        val created = request("POST", "/api/customers", token,
            """{"name":"Platform scope control","address":"Test","location":{"longitude":106.8,"latitude":-6.2}}""")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val customer = UUID.fromString(mapper.readTree(created.contentAsString).path("id").asString())
        val response = request("GET", "/api/subscriber-360/$customer", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).path("materialHistoryV2").path("items").size()).isZero()
        fixture(token).transaction {
            assertThat(context.getBean(CustomerApi::class.java).findCustomer(customer)?.id).isEqualTo(customer)
        }
    }

    @Test
    fun `same area material reads retain legacy and V2 contracts then revoked area denies the original token`() {
        val installation = mixedMaterials()
        val admin = installation.receipt.stock.token
        val reader = user(admin, setOf("customer.customer.view", "workorder.order.view"))
        grantAreas(admin, reader, listOf(area(admin)))
        val path = "/api/subscriber-360/${installation.customer}"
        val allowed = request("GET", path, reader.first)
        assertThat(allowed.status).withFailMessage(allowed.contentAsString).isEqualTo(200)
        val body = mapper.readTree(allowed.contentAsString)
        assertThat(body.path("materialHistory").single().path("quantity").asInt()).isEqualTo(37)
        assertThat(body.path("materialHistoryV2").path("totalElements").asLong()).isEqualTo(2)
        assertThat(body.path("materialHistoryV2").path("items").last().path("quantity").path("quantityBase").asString()).isEqualTo("82500")
        assertThat(request("GET", path, admin).status).isEqualTo(200)
        grantAreas(admin, reader, emptyList())
        assertDenied(path, reader.first)
    }

    @ParameterizedTest
    @ValueSource(strings = ["DIFFERENT_AREA", "EMPTY_AREAS", "FOREIGN_TENANT", "NULL_CUSTOMER_AREA"])
    fun `inaccessible customers return indistinguishable not found without material IDs or totals`(scenario: String) {
        val installation = mixedMaterials()
        val admin = installation.receipt.stock.token
        val reader = user(admin, setOf("customer.customer.view", "workorder.order.view"))
        val otherArea = request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""")
        assertThat(otherArea.status).isEqualTo(201)
        val areaId = mapper.readTree(otherArea.contentAsString).path("id").asString()
        if (scenario != "EMPTY_AREAS") grantAreas(admin, reader, listOf(areaId))
        val target = if (scenario == "NULL_CUSTOMER_AREA") {
            val created = request("POST", "/api/customers", admin,
                """{"name":"Unassigned area","address":"Test","location":{"longitude":106.8,"latitude":-6.2}}""")
            assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
            mapper.readTree(created.contentAsString).path("id").asString()
        } else installation.customer.toString()
        val token = if (scenario == "FOREIGN_TENANT") tenant() else reader.first
        val before = fixture(admin).transaction { counts() }
        assertDenied("/api/subscriber-360/$target", token)
        assertThat(fixture(admin).transaction { counts() }).isEqualTo(before)
    }

    private fun grantAreas(admin: String, reader: Pair<String, String>, areas: List<String>) {
        val identity = mapper.readTree(request("GET", "/api/me", reader.first).contentAsString)
        val response = request("PUT", "/api/users/${reader.second}/access", admin,
            mapper.writeValueAsString(mapOf("roleIds" to identity.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to areas)))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }

    private fun assertDenied(path: String, token: String) {
        val denied = request("GET", path, token)
        assertThat(denied.status).withFailMessage("Expected inaccessible-customer404, got %s", denied.status).isEqualTo(404)
        val body: JsonNode = mapper.readTree(denied.contentAsString)
        assertThat(body.propertyNames()).doesNotContain("customer", "materialHistory", "materialHistoryV2", "totalElements", "items", "access")
        val absent = request("GET", "/api/subscriber-360/${UUID.randomUUID()}", token)
        assertThat(absent.status).isEqualTo(404)
        assertThat(body.path("title")).isEqualTo(mapper.readTree(absent.contentAsString).path("title"))
    }
}
