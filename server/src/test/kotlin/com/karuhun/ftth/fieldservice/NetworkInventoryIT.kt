package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.common.security.SecretCipher
import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.fieldservice.application.service.*
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.karuhun.ftth.iam.application.port.outbound.RoleRepository
import com.karuhun.ftth.iam.application.port.outbound.PermissionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NetworkInventoryIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var cipher: SecretCipher
    @Autowired private lateinit var roles: RoleRepository
    @Autowired private lateinit var permissions: PermissionRepository
    private val mapper = jacksonObjectMapper()
    private val base = "/api/v1/network-inventory"
    private data class Actor(val token: String, val id: UUID)
    private data class Setup(val tenant: UUID, val owner: Actor, val admin: Actor, val ne: Actor, val fo: Actor, val manager: Actor)
    private fun req(method: String, path: String, actor: Actor, body: Any? = null, key: UUID = UUID.randomUUID()) =
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + actor.token)
            .header("Idempotency-Key", key).apply {
                if (body != null) contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))
            }).andReturn().response
    private fun ok(method: String, path: String, actor: Actor, body: Any? = null, status: Int = 200, key: UUID = UUID.randomUUID()): JsonNode {
        val response = req(method, path, actor, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    private fun setup(): Setup {
        val slug = "network-" + UUID.randomUUID().toString().take(8)
        val email = "owner@" + slug + ".test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, "Network", email, "Owner", "secret12345")).tenant.id
        fun login(email: String): Actor {
            val response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("tenantSlug" to slug, "email" to email, "password" to "secret12345")))).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            val token = mapper.readTree(response.contentAsString).path("accessToken").asString()
            val actor = Actor(token, UUID(0, 0))
            return actor.copy(id = UUID.fromString(ok("GET", "/api/me", actor).path("id").asString()))
        }
        val owner = login(email)
        fun member(name: String): Actor {
            val role = ok("GET", "/api/roles", owner).single { it.path("name").asString() == name }.path("id").asString()
            val email = "member-" + UUID.randomUUID() + "@" + slug + ".test"
            ok("POST", "/api/users", owner, mapOf("name" to name, "email" to email, "password" to "secret12345", "roleIds" to listOf(role)), 201)
            return login(email)
        }
        return Setup(tenant, owner, member("Admin"), member("Teknisi NE"), member("Teknisi FO"), member("Manager"))
    }
    private fun clientInput(s: Setup, network: B2BNetworkInput? = null, revision: Long = 0, name: String = "Harper") =
        B2BClientInput(name, "Jl. Hotel 12", "PIC 08123456789", s.ne.id, 4, true, revision, network)
    private fun client(s: Setup, network: B2BNetworkInput? = null) = ok("POST", "/api/v1/b2b/clients", s.admin, clientInput(s, network), 201)
    private fun ip(s: Setup, address: String, vendor: UUID? = null) = ok("POST", base + "/ips", s.admin,
        PublicIpInput(address, "255.255.255.248", "157.15.122.99", vendor), 201)
    private fun id(row: JsonNode) = UUID.fromString(row.path("id").asString())
    private fun <T : Any> within(tenant: UUID, action: () -> T): T = TenantContext.runAs(tenant) {
        requireNotNull(TransactionTemplate(transactions).execute {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String::class.java, tenant.toString())
            action()
        })
    }

    @Test fun vendor_crud_enforces_revisions_retries_roles_and_tenant() {
        val s = setup()
        val key = UUID.randomUUID()
        val input = NetworkVendorInput("Telkom", "NOC", "Metro")
        val vendor = ok("POST", base + "/vendors", s.admin, input, 201, key)
        assertThat(ok("POST", base + "/vendors", s.admin, input, 201, key)).isEqualTo(vendor)
        assertThat(req("POST", base + "/vendors", s.admin, input.copy(name = "Indosat"), key).status).isEqualTo(409)
        assertThat(req("POST", base + "/vendors", s.admin, input.copy(name = "telkom")).status).isEqualTo(409)
        val address = ip(s, "157.15.122.100", id(vendor))
        val ipPath = base + "/ips/" + id(address)
        val changed = ok("PUT", ipPath, s.admin, PublicIpInput("157.15.122.101", vendorId = id(vendor)))
        assertThat(changed.path("revision").asLong()).isEqualTo(1)
        assertThat(req("PUT", ipPath, s.admin, PublicIpInput("157.15.122.102")).status).isEqualTo(409)
        assertThat(ok("GET", base + "/ips?q=157.15.122.101&status=AVAILABLE&vendorId=" + id(vendor), s.ne).path("content")).hasSize(1)
        assertThat(ok("GET", base + "/vendors", s.manager).path("content")).hasSize(1)
        listOf(s.ne, s.fo, s.manager).forEach {
            assertThat(req("POST", base + "/ips", it, PublicIpInput("157.15.122.103")).status).isEqualTo(403)
        }
        assertThat(req("GET", base + "/ips", s.fo).status).isEqualTo(403)
        assertThat(req("DELETE", base + "/vendors/" + id(vendor) + "?expectedRevision=0", s.admin).status).isEqualTo(409)
        val foreign = setup()
        assertThat(ok("GET", base + "/ips", foreign.owner).path("content")).isEmpty()
        assertThat(req("PUT", ipPath, foreign.admin, PublicIpInput("157.15.122.104")).status).isEqualTo(404)
        assertThat(req("DELETE", ipPath + "?expectedRevision=1", s.admin).status).isEqualTo(204)
        assertThat(req("DELETE", base + "/vendors/" + id(vendor) + "?expectedRevision=0", s.admin).status).isEqualTo(204)
    }

    @Test fun allocation_is_immediate_and_secret_is_encrypted_write_only() {
        val s = setup()
        val vendor = id(ok("POST", base + "/vendors", s.admin, NetworkVendorInput("Telkom"), 201))
        val addresses = listOf(ip(s, "157.15.122.100", vendor), ip(s, "157.15.122.101", vendor))
        val secret = "private-tunnel-" + UUID.randomUUID()
        val network = B2BNetworkInput(B2BNetworkDetails(picName = "Hotel PIC", phone = "08123456789", mainBandwidthMbps = 100,
            mainInfrastructure = "PARTNER", mainLastMile = "Metro", mainVendorId = vendor, mainCustomerId = "METRO-12",
            backupBandwidthMbps = 100, dns = listOf("8.8.8.8"), tunnelDial = "157.15.122.255", tunnelUsername = "harper",
            tunnelRemoteAddress = "157.15.122.87", tunnelLocalAddress = "157.15.122.50"), addresses.map(::id), secret)
        val key = UUID.randomUUID()
        val saved = ok("POST", "/api/v1/b2b/clients", s.admin, clientInput(s, network), 201, key)
        val clientId = id(saved)
        assertThat(saved.path("network").path("publicIps")).hasSize(2)
        assertThat(saved.path("network").path("tunnelPasswordSet").asBoolean()).isTrue()
        assertThat(saved.toString()).doesNotContain(secret, "ciphertext")
        assertThat(ok("POST", "/api/v1/b2b/clients", s.admin, clientInput(s, network), 201, key)).isEqualTo(saved)
        val read = req("GET", base + "/clients/" + clientId, s.ne)
        assertThat(read.status).isEqualTo(200)
        assertThat(read.getHeader("Cache-Control")).contains("no-store")
        assertThat(read.contentAsString).doesNotContain(secret)
        val encrypted = within(s.tenant) {
            val value = requireNotNull(jdbc.queryForObject("SELECT tunnel_password_ciphertext FROM b2b_client_network WHERE tenant_id=? AND client_id=?",
                String::class.java, s.tenant, clientId))
            val command = requireNotNull(jdbc.queryForObject("SELECT canonical || result::text FROM b2b_client_command WHERE tenant_id=? AND operation_key=?",
                String::class.java, s.tenant, key))
            assertThat(command).doesNotContain(secret).startsWith("sha256:")
            value
        }
        assertThat(encrypted).isNotEqualTo(secret)
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret)
        val preserve = network.copy(tunnelPassword = null, publicIpIds = listOf(id(addresses.first())))
        val updated = ok("PUT", "/api/v1/b2b/clients/" + clientId, s.admin, clientInput(s, preserve, name = "Harper baru"))
        assertThat(updated.path("current").path("setting").path("name").asString()).isEqualTo("Harper")
        assertThat(updated.path("network").path("publicIps")).hasSize(1)
        assertThat(updated.path("network").path("tunnelPasswordSet").asBoolean()).isTrue()
        assertThat(ok("GET", base + "/ips?status=AVAILABLE", s.admin).path("content")).hasSize(1)
        val cleared = ok("PUT", "/api/v1/b2b/clients/" + clientId, s.admin,
            clientInput(s, preserve.copy(clearTunnelPassword = true), revision = 1))
        assertThat(cleared.path("network").path("tunnelPasswordSet").asBoolean()).isFalse()
    }

    @Test fun conflict_rolls_back_client_and_deletion_releases_ip() {
        val s = setup()
        val address = ip(s, "157.15.122.100")
        val network = B2BNetworkInput(publicIpIds = listOf(id(address)))
        val saved = client(s, network)
        assertThat(req("POST", "/api/v1/b2b/clients", s.admin, clientInput(s, network, name = "Bentrok")).status).isEqualTo(409)
        assertThat(ok("GET", "/api/v1/b2b/clients", s.admin).path("totalElements").asInt()).isEqualTo(1)
        assertThat(req("DELETE", base + "/ips/" + id(address) + "?expectedRevision=0", s.admin).status).isEqualTo(409)
        assertThat(req("PUT", base + "/ips/" + id(address), s.admin, PublicIpInput("157.15.122.101")).status).isEqualTo(409)
        assertThat(ok("GET", base + "/ips?q=Harper&status=ALLOCATED", s.ne).path("content").single().path("clientId").asString())
            .isEqualTo(id(saved).toString())
        val foreign = setup()
        assertThat(req("GET", base + "/clients/" + id(saved), foreign.ne).status).isEqualTo(404)
        assertThat(req("POST", "/api/v1/b2b/clients", foreign.admin, clientInput(foreign, network)).status).isEqualTo(404)
        assertThat(req("DELETE", "/api/v1/b2b/clients/" + id(saved) + "?expectedRevision=0", s.admin).status).isEqualTo(204)
        assertThat(ok("GET", base + "/ips?status=AVAILABLE", s.ne).path("content")).hasSize(1)
    }

    @Test fun simultaneous_clients_cannot_claim_the_same_address() {
        val s = setup()
        val address = id(ip(s, "157.15.122.100"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val commands = (1..2).map { index -> executor.submit<Int> {
                start.await()
                req("POST", "/api/v1/b2b/clients", s.admin, clientInput(s, B2BNetworkInput(publicIpIds = listOf(address)), name = "Client " + index)).status
            } }
            start.countDown()
            assertThat(commands.map { it.get(30, TimeUnit.SECONDS) }.sorted()).containsExactly(201, 409)
            assertThat(ok("GET", base + "/ips?status=ALLOCATED", s.admin).path("content")).hasSize(1)
        } finally { executor.shutdownNow() }
    }

    @Test fun invalid_addresses_subnet_and_inactive_vendors_are_rejected() {
        val s = setup()
        listOf("999.15.122.1", "157.15.122.1/29", "::1", "015.15.122.1").forEach {
            assertThat(req("POST", base + "/ips", s.admin, PublicIpInput(it)).status).isEqualTo(400)
        }
        assertThat(req("POST", base + "/ips", s.admin, PublicIpInput("157.15.122.100", subnet = "255.0.255.0")).status).isEqualTo(400)
        val vendor = id(ok("POST", base + "/vendors", s.admin, NetworkVendorInput("Inactive", active = false), 201))
        assertThat(req("POST", base + "/ips", s.admin, PublicIpInput("157.15.122.100", vendorId = vendor)).status).isEqualTo(400)
        assertThat(req("POST", "/api/v1/b2b/clients", s.admin,
            clientInput(s, B2BNetworkInput(B2BNetworkDetails(mainInfrastructure = "PARTNER", mainVendorId = vendor)))).status).isEqualTo(400)
        assertThat(req("POST", "/api/v1/b2b/clients", s.admin,
            clientInput(s, B2BNetworkInput(tunnelPassword = "new", clearTunnelPassword = true))).status).isEqualTo(400)
        assertThat(ok("GET", "/api/v1/b2b/clients", s.admin).path("content")).isEmpty()
    }

    @Test fun legacy_client_retries_and_network_permissions_remain_independent() {
        val s = setup()
        val input = clientInput(s)
        val key = UUID.randomUUID()
        val saved = ok("POST", "/api/v1/b2b/clients", s.admin, input, 201)
        within(s.tenant) {
            val legacy = mapper.writeValueAsString(mapOf("id" to null, "input" to mapOf(
                "name" to input.name, "address" to input.address, "contact" to input.contact,
                "technicianId" to input.technicianId, "target" to input.target,
                "active" to input.active, "expectedRevision" to input.expectedRevision)))
            jdbc.update("INSERT INTO b2b_client_command(tenant_id,operation_key,actor_id,canonical,result) VALUES (?,?,?,?,?::jsonb)", s.tenant, key, s.admin.id, legacy, saved.toString())
            val admin = requireNotNull(roles.findByDefaultKey("ADMIN"))
            val networkIds = permissions.findAllByIds(admin.permissionIds).filter { it.code.value.startsWith("network.ip.") }.map { it.id }.toSet()
            admin.replacePermissions(admin.permissionIds - networkIds)
            roles.save(admin)
            true
        }
        assertThat(ok("POST", "/api/v1/b2b/clients", s.admin, input, 201, key)).isEqualTo(saved)
        assertThat(req("POST", "/api/v1/b2b/clients", s.admin, input.copy(name="Different"), key).status).isEqualTo(409)
        val networked = ok("POST", "/api/v1/b2b/clients", s.owner, clientInput(s, B2BNetworkInput()), 201)
        assertThat(networked.path("network").isNull).isFalse()
        assertThat(ok("GET", "/api/v1/b2b/clients", s.admin).path("content").all { it.path("network").isNull }).isTrue()
        assertThat(req("POST", "/api/v1/b2b/clients", s.admin, clientInput(s, B2BNetworkInput())).status).isEqualTo(403)
        assertThat(req("GET", base + "/clients/" + id(networked), s.admin).status).isEqualTo(403)
    }
}
