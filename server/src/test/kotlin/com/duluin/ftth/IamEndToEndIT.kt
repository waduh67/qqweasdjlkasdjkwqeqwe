package com.duluin.ftth

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import java.util.UUID

/**
 * Uji end-to-end melalui stack HTTP nyata (MockMvc) terhadap Postgres lokal
 * (ftth_test): isolasi tenant, penegakan RBAC dinamis, dan isolasi izin platform.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IamEndToEndIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String, password: String = pass): String {
        val body = """{"tenantSlug":"$slug","email":"$email","password":"$password"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    @Test
    fun `tenant A tidak bisa melihat data tenant B`() {
        val a = "iso${uniq()}"
        val b = "iso${uniq()}"
        val adminA = "admin@$a.test"
        val adminB = "admin@$b.test"
        onboarding.onboard(OnboardTenantCommand(a, "Tenant A", adminA, "Admin A", pass))
        onboarding.onboard(OnboardTenantCommand(b, "Tenant B", adminB, "Admin B", pass))

        val tokenA = login(a, adminA)
        val usersJson = mockMvc.perform(
            get("/api/users").header("Authorization", "Bearer $tokenA"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val emails: List<String> = JsonPath.read(usersJson, "$.content[*].email")
        assertThat(emails).contains(adminA)
        assertThat(emails).doesNotContain(adminB)
    }

    @Test
    fun `user tanpa izin ditolak, dengan izin diterima`() {
        val slug = "rbac${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "RBAC Co", admin, "Admin", pass))
        val adminToken = login(slug, admin)

        // Ambil id izin iam.user.view
        val permsJson = mockMvc.perform(
            get("/api/permissions").header("Authorization", "Bearer $adminToken"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val viewPermId: String =
            JsonPath.read<List<String>>(permsJson, "$[?(@.code=='iam.user.view')].id").first()

        // Buat role Viewer (hanya iam.user.view)
        val roleJson = mockMvc.perform(
            post("/api/roles").header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Viewer","permissionIds":["$viewPermId"]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val roleId: String = JsonPath.read(roleJson, "$.id")

        // Buat user viewer
        val viewerEmail = "viewer@$slug.test"
        mockMvc.perform(
            post("/api/users").header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$viewerEmail","name":"Viewer","password":"$pass","roleIds":["$roleId"]}"""),
        ).andExpect(status().isCreated)

        val viewerToken = login(slug, viewerEmail)

        // Boleh baca users
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer $viewerToken"))
            .andExpect(status().isOk)
        // Tidak boleh buat role
        mockMvc.perform(
            post("/api/roles").header("Authorization", "Bearer $viewerToken")
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"X","permissionIds":[]}"""),
        ).andExpect(status().isForbidden)
        // Tanpa token → 401
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin tenant tidak mendapat izin platform`() {
        val slug = "plat${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Plat Co", admin, "Admin", pass))
        val token = login(slug, admin)

        val meJson = mockMvc.perform(
            get("/api/me").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val perms: List<String> = JsonPath.read(meJson, "$.permissions")
        assertThat(perms).isNotEmpty
        assertThat(perms).noneMatch { it.startsWith("platform.") }
    }
}
