package com.karuhun.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class ReferenceWorkAreaIT : WarehouseMasterHttpFixture() {
    private fun admin(owner: String): Triple<String, String, JsonNode> {
        val role = mapper.readTree(request("GET", "/api/roles", owner).contentAsString)
            .single { it.path("name").asString() == "Admin" }
        val slug = mapper.readTree(request("GET", "/api/me", owner).contentAsString)
            .path("email").asString().substringAfter('@').substringBefore(".test")
        val email = "operator@$slug.test"
        val response = request("POST", "/api/users", owner, mapper.writeValueAsString(mapOf(
            "email" to email, "name" to "Operator", "password" to "secret12345",
            "roleIds" to listOf(role.path("id").asString()))))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return Triple(login(slug, email), mapper.readTree(response.contentAsString).path("id").asString(), role)
    }

    private fun areas(token: String, suffix: String = ""): JsonNode {
        val response = request("GET", "/api/v2/work-orders/areas$suffix", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    @Test fun `operator area choices filter scope before pagination and retain named selection`() {
        val owner = tenant()
        val ids = (0..30).map { index ->
            val response = request("POST", "/api/areas", owner,
                """{"code":"AREA-$index","name":"Zone ${index.toString().padStart(2, '0')}"}""")
            assertThat(response.status).isEqualTo(201)
            mapper.readTree(response.contentAsString).path("id").asString()
        }
        val (operator, userId) = user(owner, setOf("workorder.order.create"))
        val roles = mapper.readTree(request("GET", "/api/users/$userId", owner).contentAsString).path("roleIds")
        val selected = ids.takeLast(27)
        val granted = request("PUT", "/api/users/$userId/access", owner,
            mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to selected)))
        assertThat(granted.status).withFailMessage(granted.contentAsString).isEqualTo(200)
        assertThat(request("GET", "/api/areas", operator).status).isEqualTo(403)

        val first = areas(operator, "?query=zone&size=25")
        val second = areas(operator, "?query=zone&size=25&page=1")

        assertThat(first.path("totalElements").asLong()).isEqualTo(27)
        assertThat(first.path("content")).hasSize(25)
        assertThat(second.path("content")).hasSize(2)
        assertThat((first.path("content") + second.path("content")).map { it.path("id").asString() })
            .containsExactlyElementsOf(selected)
        assertThat(areas(operator, "/${selected.last()}").path("name").asString()).isEqualTo("Zone 30")
        assertThat(request("GET", "/api/v2/work-orders/areas/${ids.first()}", operator).status).isEqualTo(404)
        assertThat(areas(operator, "?query=%").path("content")).isEmpty()
        assertThat(areas(operator, "?query=_").path("content")).isEmpty()
        val foreign = tenant()
        assertThat(request("GET", "/api/v2/work-orders/areas/${area(foreign)}", operator).status).isEqualTo(404)
        assertThat(request("PUT", "/api/users/$userId/access", owner, """{"roleIds":[]}""").status).isEqualTo(200)
        assertThat(request("GET", "/api/v2/work-orders/areas", operator).status).isEqualTo(403)
    }

    @Test fun `area choices require an operator grant and valid paging`() {
        val owner = tenant()
        val (viewer, _) = user(owner, setOf("workorder.order.view"))
        assertThat(request("GET", "/api/v2/work-orders/areas", viewer).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/work-orders/areas", null).status).isEqualTo(401)
        for (suffix in listOf("?page=-1", "?size=0", "?size=201", "?query=" + "a".repeat(201)))
            assertThat(request("GET", "/api/v2/work-orders/areas$suffix", owner).status).isEqualTo(400)
    }

    @Test fun `default admin without assignments sees current and newly added tenant areas`() {
        val owner = tenant()
        val (operator, _, _) = admin(owner)
        assertThat(request("GET", "/api/areas", operator).status).isEqualTo(403)
        assertThat(areas(operator).path("totalElements").asLong()).isEqualTo(1)
        val added = (0..25).map { index ->
            val response = request("POST", "/api/areas", owner,
                """{"code":"NEW-$index","name":"New zone ${index.toString().padStart(2, '0')}"}""")
            assertThat(response.status).isEqualTo(201)
            mapper.readTree(response.contentAsString).path("id").asString()
        }
        val first = areas(operator, "?query=new&size=25")
        val second = areas(operator, "?query=new&size=25&page=1")
        assertThat(first.path("totalElements").asLong()).isEqualTo(26)
        assertThat(first.path("content")).hasSize(25)
        assertThat(second.path("content")).hasSize(1)
        assertThat((first.path("content") + second.path("content")).map { it.path("id").asString() })
            .containsExactlyElementsOf(added)
        assertThat(areas(operator, "/${added.last()}").path("name").asString()).isEqualTo("New zone 25")
        val foreign = tenant()
        assertThat(request("GET", "/api/v2/work-orders/areas/${area(foreign)}", operator).status).isEqualTo(404)
    }

    @Test fun `admin explicit assignments and role revocation apply to an existing token`() {
        val owner = tenant()
        val (operator, userId, role) = admin(owner)
        val extra = request("POST", "/api/areas", owner, """{"code":"SECOND","name":"Second area"}""")
        assertThat(extra.status).isEqualTo(201)
        val extraId = mapper.readTree(extra.contentAsString).path("id").asString()
        assertThat(areas(operator).path("totalElements").asLong()).isEqualTo(2)
        val roles = listOf(role.path("id").asString())
        val grant = request("PUT", "/api/users/$userId/access", owner,
            mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to listOf(area(owner)))))
        assertThat(grant.status).isEqualTo(200)
        assertThat(areas(operator).path("content").asSequence().map { it.path("id").asString() }.toList()).containsExactly(area(owner))
        assertThat(request("GET", "/api/v2/work-orders/areas/$extraId", operator).status).isEqualTo(404)
        val clear = request("PUT", "/api/users/$userId/access", owner,
            mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to emptyList<String>())))
        assertThat(clear.status).isEqualTo(200)
        assertThat(areas(operator).path("totalElements").asLong()).isEqualTo(2)
        assertThat(request("PUT", "/api/users/$userId/access", owner, """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        assertThat(request("GET", "/api/v2/work-orders/areas", operator).status).isEqualTo(403)
    }

    @Test fun `area default follows the builtin role identity rather than its display name`() {
        val owner = tenant()
        val (operator, userId, role) = admin(owner)
        val renamed = request("PUT", "/api/roles/${role.path("id").asString()}", owner, mapper.writeValueAsString(mapOf(
            "name" to "Dispatcher", "permissionIds" to role.path("permissionIds"))))
        assertThat(renamed.status).isEqualTo(200)
        assertThat(areas(operator).path("totalElements").asLong()).isEqualTo(1)
        val custom = request("POST", "/api/roles", owner, mapper.writeValueAsString(mapOf(
            "name" to "Admin", "permissionIds" to role.path("permissionIds"))))
        assertThat(custom.status).isEqualTo(201)
        val replacement = request("PUT", "/api/users/$userId/access", owner, mapper.writeValueAsString(mapOf(
            "roleIds" to listOf(mapper.readTree(custom.contentAsString).path("id").asString()), "areaIds" to emptyList<String>())))
        assertThat(replacement.status).isEqualTo(200)
        assertThat(areas(operator).path("content")).isEmpty()
    }
}
