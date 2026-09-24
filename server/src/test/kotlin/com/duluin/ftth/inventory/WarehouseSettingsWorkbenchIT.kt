package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WarehouseSettingsWorkbenchIT : WarehousePolicyHttpFixture() {
    private val root = "/api/v1/warehouse/settings/workbench"

    @Test fun `named history filters inaccessible versions before total and page without IAM read permission`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.source, setup.inspection), listOf(checker.second)))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second), revision = 1))
        val viewer = approver(setup.token, listOf(setup.inspection), setOf("inventory.approval.view"))
        val response = request("GET", "$root/policy-history?size=1", viewer.first)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        val page = mapper.readTree(response.contentAsString)
        assertThat(page.path("totalElements").asLong()).isEqualTo(1)
        val version = page.path("items").single()
        assertThat(version.path("current").path("revision").asLong()).isEqualTo(2)
        assertThat(version.path("references").path("users").asSequence().any { it.path("name").asString() == "Viewer" }).isTrue()
        assertThat(version.path("references").path("locations").single().path("id").asString()).isEqualTo(setup.inspection)
        assertThat(response.contentAsString).doesNotContain("password", "email", "areaIds")
        assertThat(request("GET", "/api/users", viewer.first).status).isEqualTo(403)
        assertThat(mapper.readTree(request("GET", "$root/policy-history?size=1&page=1", viewer.first).contentAsString).path("items").size()).isZero()
        for (suffix in listOf("?page=0&page=1", "?size=101", "?locationId=${setup.source}"))
            assertThat(request("GET", "$root/policy-history$suffix", viewer.first).status).isEqualTo(400)
    }

    @Test fun `delegation choices preserve policy role authority and exclude chains while paged reads scope current locations`() {
        val setup = setupReceipt()
        val locations = listOf(setup.source, setup.inspection)
        val source = approver(setup.token, locations)
        val delegate = approver(setup.token, locations)
        approver(setup.token, locations)
        val sourceRole = mapper.readTree(request("GET", "/api/users/${source.second}", setup.token).contentAsString).path("roleIds").single().asString()
        configure(setup.token, mapper.writeValueAsString(mapOf("expectedRevision" to 0, "currency" to "IDR", "expiryHours" to 24,
            "warehouseIds" to locations, "rules" to listOf(mapOf("operation" to "RECEIPT", "tiers" to listOf(mapOf(
                "minimumMinor" to "1", "userIds" to listOf(source.second, delegate.second), "roleIds" to listOf(sourceRole))))))))
        val path = "$root/delegation-candidates?locationId=${setup.source}&operation=RECEIPT"
        val sources = request("GET", "$path&kind=APPROVER&sourceRoleId=$sourceRole", setup.token)
        assertThat(sources.status).withFailMessage(sources.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(sources.contentAsString).path("items").single().path("id").asString()).isEqualTo(source.second)
        val targets = request("GET", "$path&kind=DELEGATE&approverId=${source.second}&sourceRoleId=$sourceRole&size=1", setup.token)
        assertThat(targets.status).withFailMessage(targets.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(targets.contentAsString).path("totalElements").asLong()).isEqualTo(2)
        fun create(location: String): String {
            val result = request("POST", "/api/v1/warehouse/settings/delegations", setup.token, mapper.writeValueAsString(mapOf(
                "expectedRevision" to 0, "approverId" to source.second, "delegateId" to delegate.second, "sourceRoleId" to sourceRole,
                "locationId" to location, "operation" to "RECEIPT", "validUntil" to Instant.now().plusSeconds(3600).toString())))
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
            return mapper.readTree(result.contentAsString).path("id").asString()
        }
        val visible = create(setup.source)
        create(setup.inspection)
        val viewer = approver(setup.token, listOf(setup.source), setOf("inventory.approval.view"))
        val scoped = request("GET", "$root/delegations?state=ACTIVE&size=1", viewer.first)
        assertThat(scoped.status).withFailMessage(scoped.contentAsString).isEqualTo(200)
        val page = mapper.readTree(scoped.contentAsString)
        assertThat(page.path("totalElements").asLong()).isEqualTo(1)
        assertThat(page.path("items").single().path("delegation").path("id").asString()).isEqualTo(visible)
        assertThat(page.path("items").single().path("sourceRole").path("id").asString()).isEqualTo(sourceRole)
        assertThat(page.path("items").single().path("delegate").path("name").asString()).isEqualTo("Viewer")
        assertThat(request("GET", "$path&kind=APPROVER", viewer.first).status).isEqualTo(403)
        assertThat(request("GET", "$path&kind=DELEGATE&approverId=${delegate.second}", setup.token).status).isEqualTo(404)
        val currentSources = mapper.readTree(request("GET", "$path&kind=APPROVER", setup.token).contentAsString)
        assertThat(currentSources.path("items").asSequence().map { it.path("id").asString() }.toList()).containsExactly(source.second)
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations/$visible/revoke", setup.token, """{"expectedRevision":1}""").status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "$root/delegations?state=ACTIVE", viewer.first).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(mapper.readTree(request("GET", "$root/delegations?state=REVOKED", viewer.first).contentAsString).path("totalElements").asLong()).isEqualTo(1)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${viewer.second}/${setup.source}", setup.token, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "$root/delegations", viewer.first).contentAsString).path("totalElements").asLong()).isZero()
    }
}
