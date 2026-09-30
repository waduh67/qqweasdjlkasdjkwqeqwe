package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class WarehouseCompatibilityIT : CustomerDeploymentFixture() {
    @Test
    fun `PSB preserves service and work order without assigning physical equipment`() {
        val token = tenant()
        val plan = plan(token)
        val response = request("POST", "/api/onboarding/psb", token,
            """{"name":"Compatibility PSB","address":"Test","areaId":"${area(token)}","location":{"longitude":106.8,"latitude":-6.2},"planId":"$plan","username":"compatibility","secret":"local-test-only"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        val result = mapper.readTree(response.contentAsString)
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("customerId", "subscriptionId", "accessId", "username", "workOrderId", "workOrderCode")
        val customer = read(token, "/api/customers/${result.path("customerId").asString()}")
        assertThat(customer.path("subscription").path("status").asString()).isEqualTo("PENDING")
        assertThat(customer.path("onus").size()).isZero()
        val work = read(token, "/api/work-orders/${result.path("workOrderId").asString()}").path("workOrder")
        assertThat(work.path("type").asString()).isEqualTo("PSB")
        assertThat(work.path("subscriptionId")).isEqualTo(result.path("subscriptionId"))
        assertPhysicalEmpty(token)
    }

    @Test
    fun `customer import preserves row outcomes without implicit ONU or asset assignment`() {
        val token = tenant()
        plan(token)
        val response = request("POST", "/api/onboarding/import/customers", token,
            """{"mode":"ALREADY_INSTALLED","operationKey":"compatibility-import","rows":[
                {"name":"Imported","address":"Test","packageName":"Compatibility","mikrotikUsername":"imported"},
                {"mikrotikUsername":"bad","connectionType":"invalid"},{}]}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val result = mapper.readTree(response.contentAsString)
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("created", "updated", "skipped", "failed", "rows")
        assertThat(result.path("created").asInt()).isEqualTo(1)
        assertThat(result.path("failed").asInt()).isEqualTo(1)
        assertThat(result.path("skipped").asInt()).isEqualTo(1)
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM subscription")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM migration_fulfillment_inbox WHERE state='PENDING'")).isEqualTo("1")
        }
        assertPhysicalEmpty(token)
    }

    @Test
    fun `legacy ONU reads preserve identity and raw new registration stays denied`() {
        val token = tenant()
        val response = request("POST", "/api/customers", token,
            """{"name":"Legacy","address":"Test","location":{"longitude":106.8,"latitude":-6.2}}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(response.contentAsString).path("id").asString()
        val id = LegacyOnuTestFixture.stage(customer, "LEGACY-COMPATIBILITY")
        val onu = read(token, "/api/customers/$customer/onus").single()
        assertThat(onu.path("id").asString()).isEqualTo(id)
        assertThat(onu.path("customerId").asString()).isEqualTo(customer)
        assertThat(onu.path("serialNumber").asString()).isEqualTo("LEGACY-COMPATIBILITY")
        assertThat(onu.propertyNames()).containsAll(legacyOnuFields)
        val denied = request("POST", "/api/customers/$customer/onus", token,
            """{"serialNumber":"NEW-RAW","model":"Manual"}""")
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(denied.contentAsString).path("code").asString()).isEqualTo("USE_WORKORDER_ASSET_WORKFLOW")
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM onu")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("0")
        }
    }

    private fun plan(token: String): String {
        val response = request("POST", "/api/catalog/plans", token,
            """{"name":"Compatibility","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    private fun read(token: String, path: String): JsonNode {
        val response = request("GET", path, token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    private fun assertPhysicalEmpty(token: String) = fixture(token).transaction {
        for (table in listOf("onu", "inventory_serialized_asset", "inventory_asset_assignment", "inventory_movement")) {
            assertThat(scalar("SELECT count(*) FROM $table")).describedAs(table).isEqualTo("0")
        }
    }

    private val legacyOnuFields = listOf("id", "customerId", "serialNumber", "model", "odpId", "odpCode", "odpPortNumber",
        "installRxPowerDbm", "opticalHealth", "status", "installedAt")
}
