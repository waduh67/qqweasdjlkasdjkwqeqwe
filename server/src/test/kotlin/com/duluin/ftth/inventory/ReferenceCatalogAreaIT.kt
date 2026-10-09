package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReferenceCatalogAreaIT : WarehouseMasterHttpFixture() {
    @Test fun `default Admin selects only explicitly granted tenant areas without IAM directory access`() {
        val owner = tenant()
        val parent = area(owner)
        val childResponse = request("POST", "/api/areas", owner,
            """{"code":"CHILD","name":"Area anak","parentId":"$parent"}""")
        assertThat(childResponse.status).isEqualTo(201)
        val child = mapper.readTree(childResponse.contentAsString).path("id").asString()
        val foreign = tenant()
        val foreignArea = area(foreign)
        val me = mapper.readTree(request("GET", "/api/me", owner).contentAsString)
        val slug = me.path("email").asString().substringAfter('@').substringBefore(".test")
        val role = mapper.readTree(request("GET", "/api/roles", owner).contentAsString)
            .single { it.path("name").asString() == "Admin" }.path("id").asString()
        val email = "catalog@$slug.test"
        val created = request("POST", "/api/users", owner, mapper.writeValueAsString(mapOf(
            "name" to "Admin gudang", "email" to email, "password" to "secret12345", "roleIds" to listOf(role))))
        assertThat(created.status).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        fun grant(ids: List<String>) {
            assertThat(request("PUT", "/api/users/$id/access", owner, mapper.writeValueAsString(
                mapOf("roleIds" to listOf(role), "areaIds" to ids))).status).isEqualTo(200)
        }
        grant(listOf(parent))
        val admin = login(slug, email)
        assertThat(request("GET", "/api/areas", admin).status).isEqualTo(403)
        val first = request("GET", "/api/v2/warehouse/areas", admin)
        assertThat(first.status).isEqualTo(200)
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(mapper.readTree(first.contentAsString).toList().map { it.path("id").asString() }).containsExactly(parent)
        grant(listOf(parent, child))
        val scoped = mapper.readTree(request("GET", "/api/v2/warehouse/areas", admin).contentAsString)
        assertThat(scoped.toList().map { it.path("id").asString() }).containsExactlyInAnyOrder(parent, child)
            .doesNotContain(foreignArea)
        assertThat(request("POST", "/api/v2/warehouse/workflow/drain", owner,
            """{"expectedEpoch":0}""").status).isEqualTo(200)
        val review = mapper.readTree(request("GET", "/api/v2/warehouse/workflow/review", owner).contentAsString)
        assertThat(request("POST", "/api/v2/warehouse/workflow/activate", owner,
            """{"expectedEpoch":1,"reviewHash":"${review.path("reviewHash").asString()}","reason":"Alur baru"}""").status).isEqualTo(200)
        val location = request("POST", "/api/v2/warehouse/locations", admin,
            """{"code":"CHILD-WH","name":"Gudang anak","kind":"WAREHOUSE","areaId":"$child","issueEligible":true}""")
        assertThat(location.status).withFailMessage(location.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/v2/warehouse/locations", admin,
            """{"code":"FOREIGN","name":"Area asing","kind":"WAREHOUSE","areaId":"$foreignArea"}""").status).isEqualTo(404)
        grant(emptyList())
        val revoked = request("GET", "/api/v2/warehouse/areas", admin)
        assertThat(revoked.status).isEqualTo(200)
        assertThat(mapper.readTree(revoked.contentAsString).isEmpty).isTrue()
    }

    @Test fun `reference area choices require catalog permission and owner choices stay within the tenant`() {
        val owner = tenant()
        val foreign = tenant()
        val (denied, _) = user(owner, setOf("warehouse.request.own"))
        assertThat(request("GET", "/api/v2/warehouse/areas", denied).status).isEqualTo(403)
        val result = request("GET", "/api/v2/warehouse/areas", owner)
        assertThat(result.status).isEqualTo(200)
        val ids = mapper.readTree(result.contentAsString).toList().map { UUID.fromString(it.path("id").asString()) }
        assertThat(ids).contains(UUID.fromString(area(owner))).doesNotContain(UUID.fromString(area(foreign)))
    }
}
