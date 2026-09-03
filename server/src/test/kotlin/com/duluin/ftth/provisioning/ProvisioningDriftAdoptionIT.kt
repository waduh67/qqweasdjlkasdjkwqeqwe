package com.duluin.ftth.provisioning

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
class ProvisioningDriftAdoptionIT {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var entityManager: EntityManager

    @Test
    fun `semantically equivalent certified drift stores ownership baseline and audit evidence`() {
        val slug = "adopt-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        val fixture = asTenant(tenant.id) { insertFixture(tenant.id) }
        val token = login(slug, email)

        val response = mockMvc.perform(
            post("/api/provisioning/drift/${fixture.driftId}/adopt")
                .header("Authorization", "Bearer $token")
                .header("If-Match", "W/\"1\""),
        ).andReturn().response

        assertThat(response.status).isEqualTo(200)
        assertThat(JsonPath.read<String>(response.contentAsString, "$.status")).isEqualTo("NONE")
        asTenant(tenant.id) {
            assertThat(count("provisioning_adoption_baseline", "drift_id", fixture.driftId)).isEqualTo(1)
            assertThat(count("provisioning_device_snapshot", "device_id", fixture.deviceId)).isEqualTo(2)
        }
        val audit = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/audit-logs?page=0&size=100")
                .header("Authorization", "Bearer $token"),
        ).andReturn().response.contentAsString
        assertThat(audit).contains("provisioning.drift.adopted")
    }

    private fun insertFixture(tenantId: UUID): Fixture {
        val poolId = UUID.randomUUID()
        val profileId = UUID.randomUUID()
        val intentId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        val driftId = UUID.randomUUID()
        val capabilityId = UUID.randomUUID()
        val collectorId = UUID.randomUUID()
        val reportId = UUID.randomUUID()
        val now = Instant.now()
        sql("INSERT INTO provisioning_vlan_pool (id,tenant_id,name,vlan_start,vlan_end) VALUES (:a,:t,'adopt',100,200)", poolId, tenantId)
        entityManager.createNativeQuery("INSERT INTO provisioning_segment_profile (id,tenant_id,name,pool_id) VALUES (:a,:t,'adopt',:pool)")
            .setParameter("a", profileId).setParameter("t", tenantId).setParameter("pool", poolId).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_service_intent
            (id,tenant_id,subscription_id,segment_profile_id,encapsulation,status)
            VALUES (:a,:t,:subscription,:profile,'SINGLE_TAG','DRAFT')""")
            .setParameter("a", intentId).setParameter("t", tenantId).setParameter("subscription", UUID.randomUUID())
            .setParameter("profile", profileId).executeUpdate()
        entityManager.createNativeQuery("INSERT INTO provisioning_plan (id,tenant_id,intent_id,revision,status,content_hash) VALUES (:a,:t,:intent,1,'GENERATED',:hash)")
            .setParameter("a", planId).setParameter("t", tenantId).setParameter("intent", intentId)
            .setParameter("hash", "0".repeat(64)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_device_snapshot
            (id,tenant_id,device_kind,device_id,plan_id,normalized_state,captured_at)
            VALUES (:a,:t,'ROUTER',:device,:plan,CAST(:state AS jsonb),:now)""")
            .setParameter("a", snapshotId).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("plan", planId).setParameter("state", BASELINE).setParameter("now", now).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_device_observation
            (id,tenant_id,device_kind,device_id,normalized_state,observed_at)
            VALUES (:a,:t,'ROUTER',:device,CAST(:state AS jsonb),:now)""")
            .setParameter("a", observationId).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("state", OBSERVED).setParameter("now", now).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_drift_record
            (id,tenant_id,device_kind,device_id,snapshot_id,observation_id,status,recorded_at)
            VALUES (:a,:t,'ROUTER',:device,:snapshot,:observation,'BENIGN',:now)""")
            .setParameter("a", driftId).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("snapshot", snapshotId).setParameter("observation", observationId).setParameter("now", now).executeUpdate()
        insertCertificationEvidence(tenantId, deviceId, collectorId, reportId, capabilityId, observationId, now)
        return Fixture(driftId, deviceId)
    }

    private fun insertCertificationEvidence(
        tenantId: UUID,
        deviceId: UUID,
        collectorId: UUID,
        reportId: UUID,
        capabilityId: UUID,
        observationId: UUID,
        now: Instant,
    ) {
        entityManager.createNativeQuery("""INSERT INTO collector
            (id,tenant_id,name,api_key_hash,api_key_hint,status,poll_interval_seconds)
            VALUES (:a,:t,'adoption-collector',:hash,'task14','ACTIVE',60)""")
            .setParameter("a", collectorId).setParameter("t", tenantId)
            .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_collector_device_report
            (id,tenant_id,collector_id,report_key,target_id,vendor,model,firmware,transport,capabilities,
             operation_classes,reported_at,expires_at)
            VALUES (:a,:t,:collector,:key,:target,'MIKROTIK','CCR2004','7.20.2','HTTPS_REST','VLAN',
                    'ENSURE_TAGGED_VLAN',:now,:expires)""")
            .setParameter("a", reportId).setParameter("t", tenantId).setParameter("collector", collectorId)
            .setParameter("key", "$deviceId@$now").setParameter("target", deviceId.toString())
            .setParameter("now", now).setParameter("expires", now.plusSeconds(600)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_capability_evidence
            (id,tenant_id,collector_id,report_id,device_kind,device_id,vendor,model,firmware,transport,
             operation_class,supported,observed_at,expires_at)
            VALUES (:a,:t,:collector,:report,'ROUTER',:device,'MIKROTIK','CCR2004','7.20.2','HTTPS_REST',
                    'ENSURE_TAGGED_VLAN',true,:now,:expires)""")
            .setParameter("a", capabilityId).setParameter("t", tenantId).setParameter("collector", collectorId)
            .setParameter("report", reportId).setParameter("device", deviceId).setParameter("now", now)
            .setParameter("expires", now.plusSeconds(600)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_adapter_certification
            (id,tenant_id,device_kind,device_id,vendor,model,firmware,transport,operation_class,status,
             valid_until,evidence_id,certified_by,certified_at)
            VALUES (:a,:t,'ROUTER',:device,'MIKROTIK','CCR2004','7.20.2','HTTPS_REST','ENSURE_TAGGED_VLAN',
                    'CERTIFIED',:expires,:evidence,:actor,:now)""")
            .setParameter("a", UUID.randomUUID()).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("expires", now.plusSeconds(600)).setParameter("evidence", capabilityId)
            .setParameter("actor", UUID.randomUUID()).setParameter("now", now).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_management_safety_evidence
            (id,tenant_id,device_kind,device_id,protected_vlan_ranges,protected_ip_prefixes,protected_vrfs,
             protected_interface_roles,protected_collector_paths,protected_oob_routes,available_oob_routes,
             observed_at,valid_until,complete,source_type,device_observation_source_id)
            VALUES (:a,:t,'ROUTER',:device,'','','','','','','',:now,:expires,true,'DEVICE_OBSERVATION',:source)""")
            .setParameter("a", UUID.randomUUID()).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("now", now).setParameter("expires", now.plusSeconds(600))
            .setParameter("source", observationId).executeUpdate()
    }

    private fun sql(statement: String, id: UUID, tenantId: UUID) = entityManager.createNativeQuery(statement)
        .setParameter("a", id).setParameter("t", tenantId).executeUpdate()

    private fun count(table: String, column: String, id: UUID): Long =
        (entityManager.createNativeQuery("SELECT count(*) FROM $table WHERE $column = :id").setParameter("id", id).singleResult as Number).toLong()

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"tenantSlug":"$slug","email":"$email","password":"$PASSWORD"}""")).andReturn().response.contentAsString,
        "$.accessToken",
    )

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private data class Fixture(val driftId: UUID, val deviceId: UUID)

    private companion object {
        const val PASSWORD = "secret12345"
        const val BASELINE = "{\"vlanId\":110,\"external\":false}"
        const val OBSERVED = "{\"vlanId\":110,\"external\":true}"
    }
}
