package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class WarehouseMasterHttpFixture {
    @Autowired protected lateinit var mvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired protected lateinit var context: org.springframework.context.ConfigurableApplicationContext
    protected val mapper = jacksonObjectMapper()
    private val tenantAreas = mutableMapOf<String, String>()

    protected fun tenant(slug: String = "master${UUID.randomUUID().toString().take(8)}"): String {
        onboarding.onboard(OnboardTenantCommand(slug, "Master test", "admin@$slug.test", "Admin", "secret12345"))
        val token = login(slug, "admin@$slug.test")
        val area = request("POST", "/api/areas", token, """{"code":"MAIN","name":"Main area"}""")
        assertThat(area.status).isEqualTo(201)
        val areaId = mapper.readTree(area.contentAsString).path("id").asString()
        val me = mapper.readTree(request("GET", "/api/me", token).contentAsString)
        val access = request("PUT", "/api/users/${me.path("id").asString()}/access", token,
            mapper.writeValueAsString(mapOf("roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(areaId))))
        assertThat(access.status).withFailMessage(access.contentAsString).isEqualTo(200)
        tenantAreas[token] = areaId
        return token
    }

    protected fun area(token: String): String = requireNotNull(tenantAreas[token])

    internal fun fixture(token: String): WarehousePostingFixture {
        val tenantId = UUID.fromString(mapper.readTree(request("GET", "/api/me", token).contentAsString).path("tenantId").asString())
        return WarehousePostingFixture(context, tenantId)
    }

    protected fun login(slug: String, email: String): String {
        val response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"tenantSlug":"$slug","email":"$email","password":"secret12345"}""")).andReturn().response
        assertThat(response.status).isEqualTo(200)
        return mapper.readTree(response.contentAsString).path("accessToken").asString()
    }

    protected fun request(method: String, path: String, token: String?, body: String? = null, key: String = UUID.randomUUID().toString()) =
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path).apply {
            if (token != null) header("Authorization", "Bearer $token")
            header("Idempotency-Key", key)
            if (body != null) contentType(MediaType.APPLICATION_JSON).content(body)
        }).andReturn().response

    protected fun create(resource: String, token: String, body: String): tools.jackson.databind.JsonNode {
        val scopedBody = if (resource == "locations" && !body.contains("\"areaId\"")) body.dropLast(1) + ",\"areaId\":\"${area(token)}\"}" else body
        val response = request("POST", "/api/v1/warehouse/$resource", token, scopedBody)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString)
    }

    protected fun user(admin: String, permissions: Set<String>): Pair<String, String> {
        val catalog = mapper.readTree(request("GET", "/api/permissions", admin).contentAsString)
        val permissionIds = catalog.filter { it.path("code").asString() in permissions }.map { it.path("id").asString() }
        assertThat(permissionIds).hasSize(permissions.size)
        val role = request("POST", "/api/roles", admin, mapper.writeValueAsString(mapOf("name" to UUID.randomUUID().toString(), "permissionIds" to permissionIds)))
        assertThat(role.status).isEqualTo(201)
        val me = mapper.readTree(request("GET", "/api/me", admin).contentAsString)
        val slug = me.path("email").asString().substringAfter('@').substringBefore(".test")
        val email = "viewer${UUID.randomUUID().toString().take(8)}@$slug.test"
        val response = request("POST", "/api/users", admin, mapper.writeValueAsString(mapOf("email" to email, "name" to "Viewer",
            "password" to "secret12345", "roleIds" to listOf(mapper.readTree(role.contentAsString).path("id").asString()))))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return login(slug, email) to mapper.readTree(response.contentAsString).path("id").asString()
    }
}
