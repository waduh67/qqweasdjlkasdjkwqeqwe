package com.duluin.ftth.tenancy

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.common.security.JwtClaims
import com.duluin.ftth.common.security.SessionIdentity
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.DeliveryAuthorityApi
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantOwnerIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var roles: RoleRepository
    @Autowired private lateinit var manager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var encoder: JwtEncoder
    @Autowired private lateinit var authority: DeliveryAuthorityApi
    @Autowired private lateinit var throttle: AttemptThrottle
    private val password = "secret12345"
    private val replacement = "replacement12345"

    @BeforeEach fun resetThrottle() = throttle.clear()
    private data class Fixture(val id: UUID, val email: String, val owner: UUID)
    private fun fixture(): Fixture {
        val slug = "owners-${UUID.randomUUID().toString().take(8)}"
        val email = "owner@$slug.test"
        val id = onboarding.onboard(OnboardTenantCommand(slug, "Owner ISP", email, "Owner", password)).tenant.id
        return Fixture(id, email, within(id) { requireNotNull(users.findByEmail(Email.of(email))).id })
    }
    private fun <T : Any> within(id: UUID, action: () -> T): T = TenantContext.runAs(id) {
        requireNotNull(TransactionTemplate(manager).execute { action() })
    }
    private fun login(email: String, pass: String = password, expected: Int = 200): JsonNode {
        val result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("email" to email, "password" to pass))))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }.andReturn()
        return mapper.readTree(result.response.contentAsString)
    }
    private fun root() = login("root@ftth.local", "rootadmin123").path("accessToken").asString()
    private fun getJson(path: String, token: String): JsonNode = mapper.readTree(mvc.perform(get(path)
        .header("Authorization", "Bearer $token")).andExpect { assertThat(it.response.status).isEqualTo(200) }
        .andReturn().response.contentAsString)
    private fun reset(f: Fixture, token: String, expected: Int = 204, owner: UUID = f.owner, pass: String = replacement) {
        mvc.perform(post("/api/platform/tenants/${f.id}/owner/password").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                mapOf("expectedOwnerUserId" to owner, "newPassword" to pass))))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
    }
    private fun createUser(f: Fixture, key: String?): User = within(f.id) {
        val owner = requireNotNull(users.findById(f.owner))
        val roleIds = key?.let { setOf(requireNotNull(roles.findByDefaultKey(it)).id) }.orEmpty()
        users.save(User.create(f.id, Email.of("${UUID.randomUUID()}@owners.test"), "Operator", owner.passwordHash, roleIds = roleIds))
    }

    @Test fun `platform melihat owner tepat dan reset mencabut sesi lama tanpa menyentuh tenant lain`() {
        val f = fixture()
        val other = fixture()
        val original = login(f.email)
        val unaffected = login(other.email).path("accessToken").asString()
        val root = root()
        val detail = getJson("/api/platform/tenants/${f.id}", root).path("owner")
        assertThat(detail.path("id").asString()).isEqualTo(f.owner.toString())
        assertThat(detail.path("email").asString()).isEqualTo(f.email)
        assertThat(getJson("/api/platform/tenants?size=200", root).path("content").toList().map { it.path("owner").path("email").asString() }).contains(f.email, other.email)
        reset(f, root)
        mvc.perform(get("/api/me").header("Authorization", "Bearer ${original.path("accessToken").asString()}"))
            .andExpect { assertThat(it.response.status).isEqualTo(401) }
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
            mapOf("refreshToken" to original.path("refreshToken").asString())))).andExpect { assertThat(it.response.status).isEqualTo(401) }
        login(f.email, expected = 401)
        val fresh = login(f.email, replacement)
        getJson("/api/me", fresh.path("accessToken").asString())
        getJson("/api/me", unaffected)
        within(f.id) {
            val audit = jdbc.queryForMap("SELECT actor_id,detail FROM audit_log WHERE tenant_id=? AND detail::jsonb->>'@entityId'=? AND action='user.password_reset'", f.id, f.owner.toString())
            assertThat(audit["actor_id"]).isNotNull()
            assertThat(audit["detail"].toString()).doesNotContain(password, replacement, "passwordHash")
        }
    }

    @Test fun `pemilihan owner menolak user tenant lain dan identitas berubah membatalkan reset`() {
        val f = fixture()
        val other = fixture()
        val selected = createUser(f, null)
        val old = login(f.email).path("accessToken").asString()
        val root = root()
        val candidates = getJson("/api/platform/tenants/${f.id}/owner/candidates?size=200", root)
        assertThat(candidates.path("content").toList().map { it.path("id").asString() }).contains(selected.id.toString()).doesNotContain(other.owner.toString())
        mvc.perform(put("/api/platform/tenants/${f.id}/owner").header("Authorization", "Bearer $root")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("userId" to other.owner))))
            .andExpect { assertThat(it.response.status).isEqualTo(404) }
        mvc.perform(put("/api/platform/tenants/${f.id}/owner").header("Authorization", "Bearer $root")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("userId" to selected.id))))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
        reset(f, root, expected = 409)
        mvc.perform(get("/api/me").header("Authorization", "Bearer $old")).andExpect { assertThat(it.response.status).isEqualTo(401) }
        val profile = login(selected.email.value).path("user")
        val permissions = profile.path("permissions").toList().map { it.asString() }
        assertThat(permissions).contains("iam.role.create")
        assertThat(permissions.filter { it.startsWith("platform.") }).isEmpty()
        assertThat(getJson("/api/platform/tenants/${f.id}", root).path("owner").path("id").asString()).isEqualTo(selected.id.toString())
    }

    @Test fun `reset menegakkan izin minimum password dan faktor kedua tanpa mengaktifkan akun`() {
        val f = fixture()
        val ownerToken = login(f.email).path("accessToken").asString()
        reset(f, ownerToken, expected = 403)
        reset(f, root(), expected = 400, pass = "short")
        within(f.id) {
            val owner = requireNotNull(users.findById(f.owner))
            owner.beginTotpEnrollment("encrypted-test-secret")
            owner.confirmTotp(42)
            owner.disable()
            users.save(owner)
        }
        reset(f, root())
        within(f.id) {
            val owner = requireNotNull(users.findById(f.owner))
            assertThat(owner.active).isFalse()
            assertThat(owner.twoFactorEnabled).isTrue()
            assertThat(owner.totpSecret).isEqualTo("encrypted-test-secret")
            assertThat(owner.totpLastStep).isEqualTo(42)
            owner.enable()
            users.save(owner)
        }
        assertThat(login(f.email, replacement, expected = 401).path("code").asString()).isEqualTo("TWO_FACTOR_REQUIRED")
    }

    @Test fun `admin hanya reset teknisi dan sesi teknisi segera berakhir`() {
        val f = fixture()
        val admin = createUser(f, "ADMIN")
        val technician = createUser(f, "TECHNICIAN_FO")
        val manager = createUser(f, "MANAGER")
        val token = login(admin.email.value).path("accessToken").asString()
        val old = login(technician.email.value).path("accessToken").asString()
        listOf(f.owner, manager.id).forEach { id ->
            mvc.perform(post("/api/users/$id/password").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("newPassword" to replacement)))).andExpect { assertThat(it.response.status).isEqualTo(400) }
        }
        mvc.perform(post("/api/users/${technician.id}/password").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("newPassword" to replacement)))).andExpect { assertThat(it.response.status).isEqualTo(204) }
        mvc.perform(get("/api/me").header("Authorization", "Bearer $old")).andExpect { assertThat(it.response.status).isEqualTo(401) }
        login(technician.email.value, replacement)
    }

    @Test fun `token historis tanpa versi hanya berlaku sebelum reset dan identitas lama ditolak fence`() {
        val f = fixture()
        val claims = JwtClaimsSet.builder().subject(f.owner.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
            .claim(JwtClaims.TENANT_ID, f.id.toString()).claim(JwtClaims.EMAIL, f.email).claim(JwtClaims.NAME, "Owner").build()
        val legacy = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
        getJson("/api/me", legacy)
        reset(f, root())
        mvc.perform(get("/api/me").header("Authorization", "Bearer $legacy")).andExpect { assertThat(it.response.status).isEqualTo(401) }
        assertThatThrownBy { within(f.id) { authority.lockActor(SessionIdentity(f.id, f.owner, "old", 0)) } }
            .isInstanceOf(com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
    }
}
