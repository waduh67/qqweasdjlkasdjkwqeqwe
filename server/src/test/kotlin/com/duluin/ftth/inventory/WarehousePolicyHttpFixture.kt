package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat

abstract class WarehousePolicyHttpFixture : WarehouseReceiptHttpFixture() {
    protected fun approver(admin: String, locations: List<String>, permissions: Set<String> = setOf("inventory.approval.view", "inventory.approval.decide")): Pair<String, String> {
        val user = user(admin, permissions)
        grant(admin, user.second, locations)
        return user
    }
    protected fun grant(admin: String, userId: String, locations: List<String>) {
        val principal = mapper.readTree(request("GET", "/api/users/$userId", admin).contentAsString)
        val result = request("PUT", "/api/users/$userId/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin)))))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        locations.forEach { location ->
            val scope = request("PUT", "/api/v1/warehouse/settings/scopes/$userId/$location", admin, """{"expectedRevision":0,"active":true}""")
            assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
        }
    }
    protected fun policyBody(locations: List<String>, users: List<String>, operation: String = "RECEIPT", threshold: String = "100", revision: Long = 0, currency: String = "IDR") =
        mapper.writeValueAsString(mapOf("expectedRevision" to revision, "currency" to currency, "expiryHours" to 24,
            "warehouseIds" to locations, "rules" to listOf(mapOf("operation" to operation, "tiers" to listOf(
                mapOf("minimumMinor" to threshold, "userIds" to users, "roleIds" to emptyList<String>()))))))
    protected fun configure(admin: String, body: String) {
        val result = request("PUT", "/api/v1/warehouse/settings/policy", admin, body)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
    }
    protected fun evaluate(admin: String, document: String, revision: Long = 0) = request("POST", "/api/v1/warehouse/settings/evaluate", admin,
        """{"sourceDocumentId":"$document","sourceRevision":$revision}""")
    protected fun costLine(setup: Setup, cost: String? = "101", currency: String = "IDR", quantity: String = "3") =
        """{"skuId":"${setup.cable}","quantityBase":"$quantity","lotCode":"LOT"${if (cost == null) "" else ",\"cost\":{\"totalMinor\":\"$cost\",\"currency\":\"$currency\"}"}}"""
}
