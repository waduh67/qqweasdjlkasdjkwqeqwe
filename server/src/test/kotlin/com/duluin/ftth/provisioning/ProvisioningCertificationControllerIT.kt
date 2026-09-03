package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProvisioningCertificationControllerIT {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `authenticated platform admin certifies and revokes while tenant and anonymous actors are rejected`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val slug = "cert-$suffix"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", email, "Admin", PASSWORD)).tenant
        val deviceId = UuidV7.generate()
        val evidenceId = asTenant(tenant.id) { insertCapability(tenant.id, deviceId) }
        val request = """{
            "deviceKind":"BRAS","deviceId":"$deviceId","vendor":"MIKROTIK","model":"CCR2004",
            "firmware":"7.20.2","transport":"HTTPS_REST","operationClass":"ENSURE_PPPOE_TERMINATION",
            "validUntil":"${Instant.now().plusSeconds(3600)}"
        }""".trimIndent()
        val url = "/api/platform/tenants/${tenant.id}/provisioning/certifications"

        assertThat(post(url, null, request)).isEqualTo(401)
        assertThat(post(url, login(slug, email), request)).isEqualTo(403)
        val platformToken = login("platform", "root@ftth.local", "rootadmin123")
        val response = mockMvc.perform(
            post(url).header("Authorization", "Bearer $platformToken")
                .contentType(MediaType.APPLICATION_JSON).content(request),
        ).andReturn().response

        assertThat(response.status).isEqualTo(201)
        val certificationId: String = JsonPath.read(response.contentAsString, "$.id")
        assertThat(JsonPath.read<String>(response.contentAsString, "$.evidenceId")).isEqualTo(evidenceId.toString())
        val revokeStatus = post(
            "/api/platform/tenants/${tenant.id}/provisioning/certifications/$certificationId/revoke",
            platformToken,
            "",
            mapOf("If-Match" to "1"),
        )
        assertThat(revokeStatus).isEqualTo(200)
    }

    private fun post(url: String, token: String?, body: String, headers: Map<String, String> = emptyMap()): Int {
        val request = post(url).contentType(MediaType.APPLICATION_JSON).content(body)
        if (token != null) request.header("Authorization", "Bearer $token")
        headers.forEach(request::header)
        return mockMvc.perform(request).andReturn().response.status
    }

    private fun login(slug: String, email: String, password: String = PASSWORD): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$password"}"""),
        ).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun insertCapability(tenantId: UUID, deviceId: UUID): UUID {
        val collectorId = UuidV7.generate()
        val reportId = UuidV7.generate()
        val evidenceId = UuidV7.generate()
        val now = Instant.now()
        em.createNativeQuery(
            """INSERT INTO collector
               (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
               VALUES (:id, :tenant, :name, :hash, 'http', 'ACTIVE', 60)""",
        ).setParameter("id", collectorId).setParameter("tenant", tenantId)
            .setParameter("name", "collector-${UUID.randomUUID()}")
            .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2)).executeUpdate()
        em.createNativeQuery(
            """INSERT INTO provisioning_collector_device_report
               (id, tenant_id, collector_id, report_key, target_id, vendor, model, firmware, transport,
                capabilities, operation_classes, reported_at, expires_at)
               VALUES (:id, :tenant, :collector, :key, :target, 'MIKROTIK', 'CCR2004', '7.20.2', 'HTTPS_REST',
                'PPPOE_TERMINATION', 'ENSURE_PPPOE_TERMINATION', :observed, :expires)""",
        ).setParameter("id", reportId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("key", "$deviceId@$now").setParameter("target", deviceId.toString())
            .setParameter("observed", now.minusSeconds(10)).setParameter("expires", now.plusSeconds(300)).executeUpdate()
        em.createNativeQuery(
            """INSERT INTO provisioning_capability_evidence
               (id, tenant_id, collector_id, report_id, device_kind, device_id, vendor, model, firmware,
                transport, operation_class, supported, observed_at, expires_at)
               VALUES (:id, :tenant, :collector, :report, 'BRAS', :device, 'MIKROTIK', 'CCR2004', '7.20.2',
                'HTTPS_REST', 'ENSURE_PPPOE_TERMINATION', true, :observed, :expires)""",
        ).setParameter("id", evidenceId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("report", reportId).setParameter("device", deviceId)
            .setParameter("observed", now.minusSeconds(10)).setParameter("expires", now.plusSeconds(300)).executeUpdate()
        return evidenceId
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private companion object {
        const val PASSWORD = "secret12345"
    }
}
