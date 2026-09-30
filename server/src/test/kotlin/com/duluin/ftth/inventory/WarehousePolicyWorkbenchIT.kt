package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehousePolicyWorkbenchIT : WarehousePolicyHttpFixture() {
    @Test fun `named policy choices contain only independent active approvers covering every selected location`() {
        val setup = setupReceipt()
        val before = request("GET", "/api/v1/warehouse/settings/policy/details", setup.token)
        assertThat(before.status).withFailMessage(before.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(before.contentAsString).path("configured").asBoolean()).isFalse()
        val first = approver(setup.token, listOf(setup.source, setup.inspection))
        val second = approver(setup.token, listOf(setup.source, setup.inspection))
        approver(setup.token, listOf(setup.source))
        val path = "/api/v1/warehouse/settings/policy/approvers?locationId=${setup.source}&locationId=${setup.inspection}"
        val page = request("GET", "$path&size=1", setup.token)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        assertThat(page.getHeader("Cache-Control")).isEqualTo("no-store")
        val value = mapper.readTree(page.contentAsString)
        assertThat(value.path("totalElements").asLong()).isEqualTo(2)
        assertThat(value.path("items").single().path("name").asString()).isEqualTo("Viewer")
        val next = mapper.readTree(request("GET", "$path&size=1&page=1", setup.token).contentAsString)
        assertThat(listOf(value.path("items").single().path("id").asString(), next.path("items").single().path("id").asString())).containsExactlyInAnyOrder(first.second, second.second)
        assertThat(page.contentAsString).doesNotContain("email", "permissions", "areaIds", "roleIds")
        val roles = request("GET", "$path&kind=ROLE", setup.token)
        assertThat(roles.status).withFailMessage(roles.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(roles.contentAsString).path("items").size()).isEqualTo(2)
        configure(setup.token, policyBody(listOf(setup.source, setup.inspection), listOf(first.second), "ADJUSTMENT"))
        val details = request("GET", "/api/v1/warehouse/settings/policy/details", setup.token)
        assertThat(details.status).withFailMessage(details.contentAsString).isEqualTo(200)
        val saved = mapper.readTree(details.contentAsString)
        assertThat(saved.path("current").path("revision").asLong()).isEqualTo(1)
        assertThat(saved.path("references").path("users").single().path("id").asString()).isEqualTo(first.second)
        assertThat(saved.path("references").path("locations").size()).isEqualTo(2)
        assertThat(request("GET", path, first.first).status).isEqualTo(403)
        for (suffix in listOf("&size=101", "&page=0&page=1", "&kind=OTHER", "&unknown=x", "&locationId=${setup.source}"))
            assertThat(request("GET", path + suffix, setup.token).status).describedAs(suffix).isEqualTo(400)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${first.second}/${setup.inspection}", setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val revoked = mapper.readTree(request("GET", path, setup.token).contentAsString)
        assertThat(revoked.path("items").single().path("id").asString()).isEqualTo(second.second)
    }

    @Test fun `policy manager resolves named approvers without general IAM directory access`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        val manager = approver(setup.token, listOf(setup.source, setup.inspection), setOf("inventory.approval.view", "inventory.approval.manage"))
        assertThat(request("GET", "/api/users", manager.first).status).isEqualTo(403)
        val response = request("GET", "/api/v1/warehouse/settings/policy/approvers?locationId=${setup.source}&locationId=${setup.inspection}", manager.first)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).path("items").asSequence().map { it.path("id").asString() }.toList()).contains(checker.second)
        val hidden = request("GET", "/api/v1/warehouse/settings/policy/approvers?locationId=${setup.bin}", manager.first)
        assertThat(hidden.status).withFailMessage(hidden.contentAsString).isEqualTo(404)
    }
}
