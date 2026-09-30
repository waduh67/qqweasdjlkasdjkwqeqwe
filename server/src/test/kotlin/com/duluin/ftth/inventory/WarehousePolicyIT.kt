package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehousePolicyIT : WarehouseMasterHttpFixture() {
    @Test
    fun `empty tenant configures independent policy over HTTP and replays immutable version`() {
        val admin = tenant()
        val empty = request("GET", "/api/v1/warehouse/settings/policy", admin)
        assertThat(empty.status).isEqualTo(200)
        assertThat(mapper.readTree(empty.contentAsString).path("configured").asBoolean()).isFalse()
        val approver = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val warehouse = create("locations", admin, """{"code":"MAIN","name":"Main","kind":"WAREHOUSE"}""").path("id").asString()
        val principal = mapper.readTree(request("GET", "/api/users/${approver.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${approver.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${approver.second}/$warehouse", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val body = policy(warehouse, approver.second)
        val key = UUID.randomUUID().toString()
        val saved = request("PUT", "/api/v1/warehouse/settings/policy", admin, body, key)
        assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(saved.contentAsString).path("revision").asLong()).isEqualTo(1)
        assertThat(request("PUT", "/api/v1/warehouse/settings/policy", admin, body, key).contentAsString).isEqualTo(saved.contentAsString)
        assertThat(request("PUT", "/api/v1/warehouse/settings/policy", admin, body).status).isEqualTo(409)
        val history = request("GET", "/api/v1/warehouse/settings/policy/history", admin)
        assertThat(history.status).isEqualTo(200)
        assertThat(mapper.readTree(history.contentAsString).size()).isEqualTo(1)
    }

    @Test
    fun `legacy approval authority fields are rejected before request creation`() {
        val admin = tenant()
        val response = request("POST", "/api/inventory/approvals", admin,
            """{"sourceDocumentId":"${UUID.randomUUID()}","sourceRevision":0,"approverIds":["${UUID.randomUUID()}"],"tiers":[],"movementId":"${UUID.randomUUID()}"}""")
        assertThat(response.status).isEqualTo(400)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("MALFORMED_REQUEST")
    }

    @Test
    fun `scope grants require current manager and revision and never grant foreign warehouse`() {
        val admin = tenant()
        val viewer = user(admin, setOf("inventory.location.view"))
        val warehouse = create("locations", admin, """{"code":"SCOPE","name":"Scoped","kind":"WAREHOUSE"}""").path("id").asString()
        val path = "/api/v1/warehouse/settings/scopes/${viewer.second}/$warehouse"
        val body = """{"expectedRevision":0,"active":true}"""
        assertThat(request("PUT", path, viewer.first, body).status).isEqualTo(403)
        val saved = request("PUT", path, admin, body)
        assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(200)
        assertThat(request("PUT", path, admin, body).status).isEqualTo(409)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${viewer.second}/${UUID.randomUUID()}", admin, body).status).isEqualTo(404)
    }

    private fun policy(warehouse: String, approver: String) = """{"expectedRevision":0,"currency":"IDR","expiryHours":24,
        "warehouseIds":["$warehouse"],"rules":[{"operation":"ADJUSTMENT","tiers":[{"minimumMinor":"100","userIds":["$approver"],"roleIds":[]}]}]}"""
}
