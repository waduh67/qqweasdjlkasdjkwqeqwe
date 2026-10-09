package com.duluin.ftth.iam

import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import com.duluin.ftth.inventory.WarehouseMasterHttpFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class TechnicianDirectoryIT : WarehouseMasterHttpFixture() {
    private fun roles(owner: String): Map<String, JsonNode> =
        mapper.readTree(request("GET", "/api/roles", owner).contentAsString).associateBy { it.path("name").asString() }

    private fun member(owner: String, name: String, roleIds: List<String>): Pair<String, String> {
        val slug = mapper.readTree(request("GET", "/api/me", owner).contentAsString)
            .path("email").asString().substringAfter('@').substringBefore(".test")
        val email = "tech" + UUID.randomUUID().toString().take(8) + "@" + slug + ".test"
        val response = request("POST", "/api/users", owner, mapper.writeValueAsString(mapOf(
            "email" to email, "name" to name, "password" to "secret12345", "roleIds" to roleIds)))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return login(slug, email) to mapper.readTree(response.contentAsString).path("id").asString()
    }

    private fun directory(token: String, query: String = ""): JsonNode {
        val response = request("GET", "/api/technicians" + query, token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    @Test
    fun defaultAdminCanPageAndSearchRenamedNeFoWithoutRoleReadAccess() {
        val owner = tenant()
        val roles = roles(owner)
        val admin = member(owner, "Operator", listOf(roles.getValue("Admin").path("id").asString())).first
        assertThat(request("GET", "/api/roles", admin).status).isEqualTo(403)
        val ne = roles.getValue("Teknisi NE")
        val fo = roles.getValue("Teknisi FO")
        val expected = (0..30).map { index -> member(owner, "Field " + index.toString().padStart(2, '0'),
            listOf((if (index % 2 == 0) ne else fo).path("id").asString())).second }
        member(owner, "Field ordinary", emptyList())
        val disabled = member(owner, "Field disabled", listOf(ne.path("id").asString())).second
        assertThat(request("POST", "/api/users/" + disabled + "/disable", owner).status).isEqualTo(200)
        val renamed = request("PUT", "/api/roles/" + ne.path("id").asString(), owner,
            mapper.writeValueAsString(mapOf("name" to "Tim jaringan", "permissionIds" to ne.path("permissionIds"))))
        assertThat(renamed.status).withFailMessage(renamed.contentAsString).isEqualTo(200)
        val first = directory(admin, "?size=25&query=field")
        val second = directory(admin, "?size=25&page=1&query=field")
        assertThat(first.path("totalElements").asLong()).isEqualTo(31)
        assertThat(first.path("content")).hasSize(25)
        assertThat(second.path("content")).hasSize(6)
        assertThat((first.path("content") + second.path("content")).map { it.path("id").asString() })
            .containsExactlyElementsOf(expected)
        assertThat(first.path("content")[0].properties().map { it.key }).containsExactlyInAnyOrder("id", "name", "email")
        val email = first.path("content")[0].path("email").asString()
        assertThat(directory(admin, "?query=" + email).path("content")).hasSize(1)
        assertThat(directory(admin, "?query=FIELD 00").path("content")[0].path("id").asString()).isEqualTo(expected[0])
        assertThat(directory(admin, "?query=%").path("content")).isEmpty()
        assertThat(directory(admin, "?query=_").path("content")).isEmpty()
    }

    @Test
    fun pureAssignmentsExcludeMixedLegacyOwnerAndPlatformAccounts() {
        val owner = tenant()
        val roles = roles(owner)
        val ne = roles.getValue("Teknisi NE").path("id").asString()
        val fo = roles.getValue("Teknisi FO").path("id").asString()
        val adminRole = roles.getValue("Admin").path("id").asString()
        val pure = member(owner, "Pure NE FO", listOf(ne, fo)).second
        val mixed = member(owner, "Mixed", listOf(ne, adminRole)).second
        val legacyRole = request("POST", "/api/roles", owner, """{"name":"Teknisi"}""")
        assertThat(legacyRole.status).isEqualTo(201)
        val legacy = member(owner, "Legacy", listOf(mapper.readTree(legacyRole.contentAsString).path("id").asString())).second
        val me = mapper.readTree(request("GET", "/api/me", owner).contentAsString)
        assertThat(request("PUT", "/api/users/" + me.path("id").asString() + "/access", owner,
            mapper.writeValueAsString(mapOf("roleIds" to listOf(ne, adminRole)))).status).isEqualTo(200)
        fixture(owner).transaction {
            context.getBean(UserRepository::class.java).save(User.create(tenant, Email.of("platform-" + tenant + "@test.invalid"),
                "Platform technician", "unused", platformAdmin = true, roleIds = setOf(UUID.fromString(ne))))
        }
        assertThat(directory(owner).path("content").toList().map { it.path("id").asString() })
            .containsExactlyInAnyOrderElementsOf(listOf(pure, mixed, legacy))
        assertThat(directory(owner, "?pureOnly=true").path("content").toList().map { it.path("id").asString() })
            .containsExactlyElementsOf(listOf(pure))
    }

    @Test
    fun scopeIsTenantBoundAndOperationalPermissionIsRequiredOnEveryRead() {
        val owner = tenant()
        val roles = roles(owner)
        member(owner, "Local", listOf(roles.getValue("Teknisi FO").path("id").asString()))
        val foreign = tenant()
        val foreignRoles = roles(foreign)
        member(foreign, "Foreign", listOf(foreignRoles.getValue("Teknisi NE").path("id").asString()))
        assertThat(directory(owner).path("content").toList().map { it.path("name").asString() }).containsExactlyElementsOf(listOf("Local"))
        assertThat(directory(owner, "?query=Foreign").path("totalElements").asLong()).isZero()
        val manager = member(owner, "Manager", listOf(roles.getValue("Manager").path("id").asString())).first
        assertThat(request("GET", "/api/technicians", manager).status).isEqualTo(403)
        assertThat(request("GET", "/api/technicians", null).status).isEqualTo(401)
        val (operator, id) = user(owner, setOf("workorder.order.assign"))
        assertThat(directory(operator).path("totalElements").asLong()).isEqualTo(1)
        assertThat(request("PUT", "/api/users/" + id + "/access", owner, """{"roleIds":[]}""").status).isEqualTo(200)
        assertThat(request("GET", "/api/technicians", operator).status).isEqualTo(403)
    }

    @Test
    fun invalidPagingAndSearchReturnValidationErrors() {
        val owner = tenant()
        for (query in listOf("?page=-1", "?size=0", "?size=201", "?pureOnly=garbage", "?query=" + "a".repeat(201))) {
            assertThat(request("GET", "/api/technicians" + query, owner).status).isEqualTo(400)
        }
    }
}
