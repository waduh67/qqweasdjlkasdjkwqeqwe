package com.duluin.ftth.provisioning

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import com.duluin.ftth.common.tenant.TenantContext
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProvisioningResourceControllerIT {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var entityManager: EntityManager

    @Test
    fun `tenant CRUD persists revisions rejects stale update and remains isolated`() {
        val first = tenant("resource-a")
        val second = tenant("resource-b")
        val poolBody = postJson(
            "/api/provisioning/vlan-pools",
            first.token,
            """{"name":"Residential","vlanStart":100,"vlanEnd":199,"reserved":[]}""",
            201,
        )
        val poolId: String = JsonPath.read(poolBody, "$.value.id")
        assertThat(JsonPath.read<Int>(poolBody, "$.revision")).isEqualTo(1)

        val stale = sendJson(
            "PUT",
            "/api/provisioning/vlan-pools/$poolId",
            first.token,
            """{"revision":9,"name":"Changed","vlanStart":100,"vlanEnd":199,"reserved":[]}""",
        )
        assertThat(stale).isEqualTo(409)
        val missingRevision = sendJson(
            "PUT",
            "/api/provisioning/vlan-pools/$poolId",
            first.token,
            """{"name":"Changed","vlanStart":100,"vlanEnd":199,"reserved":[]}""",
        )
        assertThat(missingRevision).isEqualTo(400)

        val profile = postJson(
            "/api/provisioning/segment-profiles",
            first.token,
            """{"name":"Default","poolId":"$poolId"}""",
            201,
        )
        val profileId: String = JsonPath.read(profile, "$.value.id")
        postJson(
            "/api/provisioning/intents",
            first.token,
            """{"subscriptionId":"${UUID.randomUUID()}","segmentProfileId":"$profileId","dedicatedVlanId":null}""",
            201,
        )

        val firstPools = getJson("/api/provisioning/vlan-pools", first.token)
        val secondPools = getJson("/api/provisioning/vlan-pools", second.token)
        val audit = getJson("/api/audit-logs?page=0&size=100", first.token)
        assertThat(JsonPath.read<List<String>>(firstPools, "\$[*].value.id")).contains(poolId)
        assertThat(JsonPath.read<List<String>>(secondPools, "\$[*].value.id")).doesNotContain(poolId)
        assertThat(JsonPath.read<List<String>>(audit, "$.content[*].action"))
            .contains("provisioning.vlan_pool.created")
    }

    @Test
    fun `provisioning permission families reject anonymous requests and mutation headers are mandatory`() {
        val id = UUID.randomUUID()

        listOf(
            get("/api/provisioning/topology"),
            get("/api/provisioning/plans/$id"),
            get("/api/provisioning/drift"),
        ).forEach { request -> assertThat(mockMvc.perform(request).andReturn().response.status).isEqualTo(401) }
        listOf(
            post("/api/provisioning/vlan-pools").contentType(MediaType.APPLICATION_JSON).content("{}"),
            post("/api/provisioning/plans/$id/apply"),
            post("/api/provisioning/executions/$id/cancel"),
            post("/api/provisioning/drift/$id/adopt"),
        ).forEach { request -> assertThat(mockMvc.perform(request).andReturn().response.status).isEqualTo(401) }

        val tenant = tenant("headers")
        assertThat(
            mockMvc.perform(post("/api/provisioning/plans/$id/apply").header("Authorization", "Bearer ${tenant.token}"))
                .andReturn().response.status,
        ).isEqualTo(400)
    }

    @Test
    fun `drift adoption cannot bypass safety evidence and remains tenant isolated`() {
        val owner = tenant("drift-owner")
        val other = tenant("drift-other")
        val driftId = asTenant(owner.tenantId) { insertBenignDrift(owner.tenantId) }

        val denied = mockMvc.perform(
            post("/api/provisioning/drift/$driftId/adopt")
                .header("Authorization", "Bearer ${owner.token}")
                .header("If-Match", "\"1\""),
        ).andReturn().response

        assertThat(denied.status).isEqualTo(409)
        assertThat(denied.contentAsString).contains("DRIFT_ADOPTION_SAFETY_BLOCKED")
        assertThat(getJson("/api/provisioning/drift", other.token)).doesNotContain(driftId.toString())
    }

    private fun tenant(prefix: String): Session {
        val slug = "$prefix-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        return Session(tenant.id, login(slug, email))
    }

    private fun login(slug: String, email: String): String {
        val response = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$PASSWORD"}"""),
        ).andReturn().response.contentAsString
        return JsonPath.read(response, "$.accessToken")
    }

    private fun postJson(url: String, token: String, body: String, expected: Int): String {
        val response = mockMvc.perform(
            post(url).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response
        assertThat(response.status).isEqualTo(expected)
        return response.contentAsString
    }

    private fun sendJson(method: String, url: String, token: String, body: String): Int {
        val builder = if (method == "PUT") put(url) else post(url)
        return mockMvc.perform(
            builder.header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.status
    }

    private fun getJson(url: String, token: String): String = mockMvc.perform(
        get(url).header("Authorization", "Bearer $token"),
    ).andReturn().response.contentAsString

    private fun insertBenignDrift(tenantId: UUID): UUID {
        val poolId = UUID.randomUUID()
        val profileId = UUID.randomUUID()
        val intentId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        val driftId = UUID.randomUUID()
        entityManager.createNativeQuery(
            "INSERT INTO provisioning_vlan_pool (id, tenant_id, name, vlan_start, vlan_end) VALUES (:id,:tenant,'drift',100,200)",
        ).setParameter("id", poolId).setParameter("tenant", tenantId).executeUpdate()
        entityManager.createNativeQuery(
            "INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id) VALUES (:id,:tenant,'drift',:pool)",
        ).setParameter("id", profileId).setParameter("tenant", tenantId).setParameter("pool", poolId).executeUpdate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_service_intent
               (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status)
               VALUES (:id,:tenant,:subscription,:profile,'SINGLE_TAG','DRAFT')""",
        ).setParameter("id", intentId).setParameter("tenant", tenantId).setParameter("subscription", UUID.randomUUID())
            .setParameter("profile", profileId).executeUpdate()
        entityManager.createNativeQuery(
            "INSERT INTO provisioning_plan (id, tenant_id, intent_id, revision, status, content_hash) VALUES (:id,:tenant,:intent,1,'GENERATED',:hash)",
        ).setParameter("id", planId).setParameter("tenant", tenantId).setParameter("intent", intentId)
            .setParameter("hash", "0".repeat(64)).executeUpdate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_device_snapshot
               (id, tenant_id, device_kind, device_id, plan_id, normalized_state, captured_at)
               VALUES (:id,:tenant,'ROUTER',:device,:plan,CAST(:state AS jsonb),now())""",
        ).setParameter("id", snapshotId).setParameter("tenant", tenantId).setParameter("device", deviceId)
            .setParameter("plan", planId).setParameter("state", "{\"vlanId\":110,\"external\":false}").executeUpdate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_device_observation
               (id, tenant_id, device_kind, device_id, normalized_state, observed_at)
               VALUES (:id,:tenant,'ROUTER',:device,CAST(:state AS jsonb),now())""",
        ).setParameter("id", observationId).setParameter("tenant", tenantId).setParameter("device", deviceId)
            .setParameter("state", "{\"vlanId\":110,\"external\":true}").executeUpdate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_drift_record
               (id, tenant_id, device_kind, device_id, snapshot_id, observation_id, status, recorded_at)
               VALUES (:id,:tenant,'ROUTER',:device,:snapshot,:observation,'BENIGN',now())""",
        ).setParameter("id", driftId).setParameter("tenant", tenantId).setParameter("device", deviceId)
            .setParameter("snapshot", snapshotId).setParameter("observation", observationId).executeUpdate()
        return driftId
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private data class Session(val tenantId: UUID, val token: String)

    private companion object { const val PASSWORD = "secret12345" }
}
