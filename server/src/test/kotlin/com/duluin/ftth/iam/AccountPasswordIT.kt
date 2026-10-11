package com.duluin.ftth.iam

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountPasswordIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var roles: RoleRepository
    @Autowired private lateinit var manager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var throttle: AttemptThrottle
    private val password = "account-original-123"
    private val replacement = "account-replaced-456"
    private data class Fixture(val id: UUID, val owner: UUID, val email: String)

    @BeforeEach fun clearThrottle() = throttle.clear()
    private fun <T : Any> within(id: UUID, action: () -> T): T = TenantContext.runAs(id) {
        requireNotNull(TransactionTemplate(manager).execute { action() })
    }
    private fun fixture(): Fixture {
        val slug = "password-${UUID.randomUUID().toString().take(8)}"
        val email = "owner@$slug.test"
        val id = onboarding.onboard(OnboardTenantCommand(slug, "Password ISP", email, "Owner", password)).tenant.id
        return Fixture(id, within(id) { requireNotNull(users.findByEmail(Email.of(email))).id }, email)
    }
    private fun user(f: Fixture, role: String? = null): User = within(f.id) {
        val original = requireNotNull(users.findById(f.owner))
        val roleIds = role?.let { setOf(requireNotNull(roles.findByDefaultKey(it)).id) }.orEmpty()
        users.save(User.create(f.id, Email.of("${UUID.randomUUID()}@password.test"), "Operator", original.passwordHash, roleIds = roleIds))
    }
    private fun post(path: String, token: String?, body: Map<String, String>, expected: Int = 204): String {
        val request = post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))
        if (token != null) request.header("Authorization", "Bearer $token")
        return mvc.perform(request).andExpect { assertThat(it.response.status).isEqualTo(expected) }.andReturn().response.contentAsString
    }
    private fun login(email: String, pass: String = password, expected: Int = 200): JsonNode = mapper.readTree(
        post("/api/auth/login", null, mapOf("email" to email, "password" to pass), expected),
    )
    private fun checkSession(token: String, expected: Int = 200) {
        mvc.perform(get("/api/me").header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
    }
    private fun checkRevoked(session: JsonNode) {
        checkSession(session.path("accessToken").asString(), 401)
        post("/api/auth/refresh", null, mapOf("refreshToken" to session.path("refreshToken").asString()), 401)
    }

    @Test fun `akun tanpa izin admin mengganti password sendiri dan seluruh sesi lama dicabut`() {
        val f = fixture()
        val account = user(f)
        val first = login(account.email.value)
        val second = login(account.email.value)
        val token = first.path("accessToken").asString()
        val body = mapOf("currentPassword" to password, "newPassword" to replacement)
        post("/api/me/password", null, body, 401)
        post("/api/me/password", token, body + ("currentPassword" to "wrong-password"), 400)
        post("/api/me/password", token, body + ("newPassword" to "short"), 400)
        post("/api/me/password", token, body + ("newPassword" to "✨".repeat(30)), 400)
        checkSession(token)
        post("/api/me/password", token, body)
        checkRevoked(first)
        checkRevoked(second)
        login(account.email.value, expected = 401)
        checkSession(login(account.email.value, replacement).path("accessToken").asString())
        within(f.id) {
            val audit = jdbc.queryForMap("SELECT actor_id,detail FROM audit_log WHERE tenant_id=? AND action='user.password_changed'", f.id)
            assertThat(audit["actor_id"].toString()).isEqualTo(account.id.toString())
            assertThat(audit["detail"].toString()).doesNotContain(password, replacement, "passwordHash")
        }
    }

    @Test fun `admin tenant mereset staf dengan izin khusus tanpa menjangkau owner dan tenant lain`() {
        val f = fixture()
        val other = fixture()
        val admin = user(f, "ADMIN")
        val staff = user(f, "MANAGER")
        val token = login(admin.email.value).path("accessToken").asString()
        val before = login(staff.email.value)
        val otherSession = login(other.email)
        val body = mapOf("newPassword" to replacement)
        post("/api/users/${staff.id}/reset-password", before.path("accessToken").asString(), body, 403)
        post("/api/users/${f.owner}/reset-password", token, body, 403)
        post("/api/users/${other.owner}/reset-password", token, body, 404)
        post("/api/users/${admin.id}/reset-password", token, body, 400)
        post("/api/users/${staff.id}/reset-password", token, body)
        checkRevoked(before)
        checkSession(token)
        checkSession(otherSession.path("accessToken").asString())
        login(staff.email.value, replacement)
        val listing = mvc.perform(get("/api/users?size=200").header("Authorization", "Bearer $token"))
            .andReturn().response.contentAsString
        val rows = mapper.readTree(listing).path("content").toList()
        assertThat(rows.first { it.path("id").asString() == f.owner.toString() }.path("tenantOwner").asBoolean()).isTrue()
        assertThat(rows.first { it.path("id").asString() == staff.id.toString() }.path("tenantOwner").asBoolean()).isFalse()
    }

    @Test fun `reset staf mempertahankan status nonaktif dan faktor kedua`() {
        val f = fixture()
        val account = user(f, "MANAGER")
        within(f.id) {
            val saved = requireNotNull(users.findById(account.id))
            saved.beginTotpEnrollment("encrypted-test-secret")
            saved.confirmTotp(42)
            saved.disable()
            users.save(saved)
        }
        val owner = login(f.email).path("accessToken").asString()
        post("/api/users/${account.id}/reset-password", owner, mapOf("newPassword" to replacement))
        within(f.id) {
            val saved = requireNotNull(users.findById(account.id))
            assertThat(saved.active).isFalse()
            assertThat(saved.twoFactorEnabled).isTrue()
        }
    }
}
