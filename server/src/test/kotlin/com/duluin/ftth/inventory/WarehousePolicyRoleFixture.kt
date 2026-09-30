package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID

abstract class WarehousePolicyRoleFixture : WarehousePolicyHttpFixture() {
    protected data class RoleScenario(val setup: Setup, val approver: Pair<String, String>, val delegate: Pair<String, String>,
        val configuredRole: String, val otherRole: String, val document: String)

    protected fun roleScenario(): RoleScenario {
        val setup = setupReceipt()
        val approver = approver(setup.token, listOf(setup.inspection))
        val delegate = approver(setup.token, listOf(setup.inspection))
        val configuredRole = userRoles(setup.token, approver.second).single()
        val otherRole = createRole(setup.token, setOf("inventory.approval.decide"))
        assignRoles(setup.token, approver.second, listOf(configuredRole, otherRole))
        configure(setup.token, rolePolicy(setup.inspection, configuredRole))
        return RoleScenario(setup, approver, delegate, configuredRole, otherRole, draft(setup, costLine(setup)).path("id").asString())
    }
    protected fun permissionIds(admin: String, permissions: Set<String>): List<String> {
        val result = request("GET", "/api/permissions", admin)
        assertThat(result.status).isEqualTo(200)
        val ids = mapper.readTree(result.contentAsString).asSequence().filter { it.path("code").asString() in permissions }
            .map { it.path("id").asString() }.toList()
        assertThat(ids).hasSize(permissions.size)
        return ids
    }
    protected fun createRole(admin: String, permissions: Set<String>): String {
        val response = request("POST", "/api/roles", admin, mapper.writeValueAsString(mapOf("name" to "Policy-${UUID.randomUUID()}",
            "permissionIds" to permissionIds(admin, permissions))))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }
    protected fun changeRole(admin: String, role: String, permissions: Set<String>) {
        val before = mapper.readTree(request("GET", "/api/roles/$role", admin).contentAsString)
        val response = request("PUT", "/api/roles/$role", admin, mapper.writeValueAsString(mapOf("name" to before.path("name").asString(),
            "permissionIds" to permissionIds(admin, permissions))))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
    protected fun userRoles(admin: String, id: String): List<String> =
        mapper.readTree(request("GET", "/api/users/$id", admin).contentAsString).path("roleIds").asSequence().map { it.asString() }.toList()
    protected fun assignRoles(admin: String, id: String, roles: List<String>) {
        val response = request("PUT", "/api/users/$id/access", admin,
            mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to listOf(area(admin)))))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
    protected fun rolePolicy(location: String, role: String, explicitUsers: List<String> = emptyList(), revision: Long = 0) =
        mapper.writeValueAsString(mapOf("expectedRevision" to revision, "currency" to "IDR", "expiryHours" to 24,
            "warehouseIds" to listOf(location), "rules" to listOf(mapOf("operation" to "RECEIPT", "tiers" to listOf(
                mapOf("minimumMinor" to "100", "userIds" to explicitUsers, "roleIds" to listOf(role)))))))
    protected fun delegationBody(scenario: RoleScenario) = mapper.writeValueAsString(mapOf("expectedRevision" to 0,
        "approverId" to scenario.approver.second, "delegateId" to scenario.delegate.second, "sourceRoleId" to scenario.configuredRole,
        "locationId" to scenario.setup.inspection, "operation" to "RECEIPT", "validUntil" to Instant.now().plusSeconds(1800).toString()))
    protected fun candidates(admin: String, document: String): Set<String> {
        val result = evaluate(admin, document)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString).path("tiers").path(0).path("approvers").asSequence().map { it.path("userId").asString() }.toSet()
    }
}
