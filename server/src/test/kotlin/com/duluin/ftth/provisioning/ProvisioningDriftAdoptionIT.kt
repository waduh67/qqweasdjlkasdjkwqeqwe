package com.duluin.ftth.provisioning

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftScanner
import com.duluin.ftth.monitoring.application.service.CollectorProvisioningExchange
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
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProvisioningDriftAdoptionIT {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var scanner: ProvisioningDriftScanner
    @Autowired private lateinit var provisioningExchange: CollectorProvisioningExchange
    @PersistenceContext private lateinit var entityManager: EntityManager

    @Test
    fun `semantically equivalent certified drift stores ownership baseline and audit evidence`() {
        val slug = "adopt-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        val support = ProvisioningDriftAdoptionFixture(entityManager, provisioningExchange)
        val fixture = asTenant(tenant.id) { support.insert(tenant.id) }
        val token = login(slug, email)

        val response = mockMvc.perform(
            post("/api/provisioning/drift/${fixture.driftId}/adopt")
                .header("Authorization", "Bearer $token")
                .header("If-Match", "W/\"1\""),
        ).andReturn().response

        assertThat(response.status).isEqualTo(200)
        assertThat(JsonPath.read<String>(response.contentAsString, "$.status")).isEqualTo("NONE")
        scanner.scan()
        asTenant(tenant.id) {
            assertThat(support.count("provisioning_device_observation", "device_id", fixture.deviceId)).isEqualTo(1)
        }
        val liveObservedAt = asTenant(tenant.id) { support.completeObservation(fixture) }
        scanner.scan()
        asTenant(tenant.id) {
            assertThat(support.count("provisioning_adoption_baseline", "drift_id", fixture.driftId)).isEqualTo(1)
            assertThat(support.count("provisioning_device_snapshot", "device_id", fixture.deviceId)).isEqualTo(2)
            assertThat(support.count("provisioning_device_observation", "device_id", fixture.deviceId)).isEqualTo(2)
            assertThat(support.countWhere("provisioning_drift_record", "device_id", fixture.deviceId, "status", "CONFLICTING")).isEqualTo(1)
            assertThat(support.latestObservationAt(fixture.deviceId).truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(liveObservedAt.truncatedTo(ChronoUnit.MILLIS))
        }
        val audit = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/audit-logs?page=0&size=100")
                .header("Authorization", "Bearer $token"),
        ).andReturn().response.contentAsString
        assertThat(audit).contains("provisioning.drift.adopted")
    }

    @Test
    fun `unrelated operation certification cannot authorize adoption`() {
        val slug = "adopt-denied-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        val support = ProvisioningDriftAdoptionFixture(entityManager, provisioningExchange)
        val fixture = asTenant(tenant.id) { support.insert(tenant.id, "REMOVE_TAGGED_VLAN") }

        val response = mockMvc.perform(
            post("/api/provisioning/drift/${fixture.driftId}/adopt")
                .header("Authorization", "Bearer ${login(slug, email)}")
                .header("If-Match", "1"),
        ).andReturn().response

        assertThat(response.status).isEqualTo(409)
        assertThat(response.contentAsString).contains("DRIFT_ADOPTION_SAFETY_BLOCKED")
        asTenant(tenant.id) {
            assertThat(support.count("provisioning_adoption_baseline", "drift_id", fixture.driftId)).isZero()
        }
    }

    @Test
    fun `observation response requires exact fence identity and deadline`() {
        val slug = "observe-bound-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        val support = ProvisioningDriftAdoptionFixture(entityManager, provisioningExchange)
        val fixture = asTenant(tenant.id) { support.insert(tenant.id) }

        scanner.scan()
        asTenant(tenant.id) {
            support.rejectMismatchedObservation(fixture)
            assertThat(support.count("provisioning_device_observation", "device_id", fixture.deviceId)).isEqualTo(1)
            assertThat(support.countWhere("provisioning_observation_request", "id", requestId(), "status", "SUCCEEDED")).isZero()
        }
    }

    @Test
    fun `unavailable live observation creates no observation or drift`() {
        val slug = "observe-unavailable-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        val support = ProvisioningDriftAdoptionFixture(entityManager, provisioningExchange)
        val fixture = asTenant(tenant.id) { support.insert(tenant.id) }

        scanner.scan()
        asTenant(tenant.id) { support.completeUnavailableObservation(fixture) }
        scanner.scan()

        asTenant(tenant.id) {
            assertThat(support.count("provisioning_device_observation", "device_id", fixture.deviceId)).isEqualTo(1)
            assertThat(support.count("provisioning_drift_record", "device_id", fixture.deviceId)).isEqualTo(1)
        }
    }

    private fun requestId(): UUID = entityManager.createNativeQuery(
        "SELECT id FROM provisioning_observation_request ORDER BY created_at DESC LIMIT 1",
    ).singleResult as UUID

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"tenantSlug":"$slug","email":"$email","password":"$PASSWORD"}""")).andReturn().response.contentAsString,
        "$.accessToken",
    )

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private companion object {
        const val PASSWORD = "secret12345"
    }
}
