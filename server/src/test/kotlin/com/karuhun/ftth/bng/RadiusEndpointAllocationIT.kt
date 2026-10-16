package com.karuhun.ftth.bng

import com.karuhun.ftth.bng.application.port.outbound.RadiusServerRepository
import com.karuhun.ftth.bng.domain.model.RadiusServer
import com.karuhun.ftth.bng.domain.model.RadiusServerStatus
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RadiusEndpointAllocationIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var servers: RadiusServerRepository
    private val createdNodes = mutableSetOf<UUID>()

    @AfterEach
    fun removeNodes() {
        createdNodes.forEach { id ->
            servers.findAssignedTenantIds(id).forEach(servers::unassignTenant)
            servers.deleteById(id)
        }
    }

    private fun tenant(): Pair<UUID, String> {
        val slug = "radius-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val created = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", "secret12345"))
        val response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"tenantSlug":"$slug","email":"$email","password":"secret12345"}""")).andReturn().response
        assertThat(response.status).isEqualTo(200)
        return created.tenant.id to JsonPath.read(response.contentAsString, "$.accessToken")
    }

    private fun node(capacity: Int = 1, id: UUID = UUID.randomUUID(), status: RadiusServerStatus = RadiusServerStatus.ACTIVE): RadiusServer {
        createdNodes.add(id)
        return servers.save(RadiusServer.create(id = id, name = "Node $id", host = "radius.example.test",
            authPort = 18120, acctPort = 18130, coaPort = 37990, sharedSecret = "fixture-radius-secret",
            dbUrl = "jdbc:postgresql://127.0.0.1:1/radius", dbUser = "fixture", dbPassword = "fixture-database-password",
            maxTenants = capacity, status = status))
    }

    private fun endpoint(token: String) = mvc.perform(get("/api/bng/radius-endpoint")
        .header("Authorization", "Bearer $token")).andReturn().response

    @Test
    fun `first endpoint read persists the tenant allocation and subsequent reads reuse it`() {
        val node = node()
        val (tenantId, token) = tenant()
        repeat(2) {
            val response = endpoint(token)
            assertThat(response.status).isEqualTo(200)
            val body = response.contentAsString
            assertThat(JsonPath.read<String>(body, "$.host")).isEqualTo(node.host)
            assertThat(JsonPath.read<Int>(body, "$.authPort")).isEqualTo(18120)
            assertThat(JsonPath.read<Int>(body, "$.acctPort")).isEqualTo(18130)
            assertThat(JsonPath.read<Int>(body, "$.coaPort")).isEqualTo(37990)
            assertThat(body).doesNotContain("fixture-database-password", "jdbc:postgresql")
            assertThat(servers.findAssignedServerForTenant(tenantId)?.id).isEqualTo(node.id)
            assertThat(servers.countAssignedTenants(node.id)).isEqualTo(1)
        }
    }

    @Test
    fun `a full node rejects a new tenant and retry succeeds after capacity is increased`() {
        val node = node()
        val (existingId, existingToken) = tenant()
        servers.assignTenant(existingId, node.id)
        val (newId, newToken) = tenant()
        assertThat(endpoint(existingToken).status).isEqualTo(200)
        val full = endpoint(newToken)
        assertThat(full.status).isEqualTo(409)
        assertThat(full.contentAsString.lowercase()).contains("kapasitas")
        assertThat(servers.findAssignedServerForTenant(newId)).isNull()
        node(capacity = 2, id = node.id)
        assertThat(endpoint(newToken).status).isEqualTo(200)
        assertThat(servers.findAssignedServerForTenant(newId)?.id).isEqualTo(node.id)
        assertThat(servers.countAssignedTenants(node.id)).isEqualTo(2)
    }

    @Test
    fun `unavailable nodes return a conflict without allocating the tenant`() {
        node(status = RadiusServerStatus.DRAINING)
        val (tenantId, token) = tenant()
        assertThat(endpoint(token).status).isEqualTo(409)
        assertThat(servers.findAssignedServerForTenant(tenantId)).isNull()
    }
}
