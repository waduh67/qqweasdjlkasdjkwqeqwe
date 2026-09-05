package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.SafetyPlanAttributes
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ManagementMutation
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningSafetyGateIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var gate: ProvisioningSafetyGate
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `persisted production evidence rejects missing provisional mismatched and stale paths`() {
        val cases = listOf(
            EvidenceCase(capability = false, expected = PolicyCode.MISSING_CAPABILITY_EVIDENCE),
            EvidenceCase(status = CertificationStatus.PROVISIONAL, expected = PolicyCode.UNCERTIFIED_CAPABILITY),
            EvidenceCase(vendor = "JUNIPER", expected = PolicyCode.FINGERPRINT_MISMATCH),
            EvidenceCase(stale = true, expected = PolicyCode.STALE_CAPABILITY_EVIDENCE),
        )

        cases.forEach { case ->
            val fixture = fixture(case)
            val decision = asTenant(fixture.tenantId) {
                gate.evaluate(fixture.plan, ExecutionMode.PRODUCTION_AUTO_APPLY)
            }
            assertThat(decision.allowed).describedAs(case.expected.name).isFalse()
            assertThat(decision.code).describedAs(case.expected.name).isEqualTo(case.expected)
        }
    }

    @Test
    fun `persisted production evidence rejects every protected management class`() {
        val available = setOf("oob/site-a")
        val cases = listOf(
            ProtectedCase(
                ProtectedManagementResources(vlanRanges = listOf(VlanRange(99, 99))),
                ManagementMutation(),
                vlanId = 99,
            ),
            ProtectedCase(
                ProtectedManagementResources(managementInterfaceRoles = setOf("MANAGEMENT")),
                ManagementMutation(interfaceRoles = setOf("MANAGEMENT")),
            ),
            ProtectedCase(
                ProtectedManagementResources(managementIpPrefixes = setOf("10.20.0.0/16")),
                ManagementMutation(ipAddresses = setOf("10.20.1.2")),
            ),
            ProtectedCase(ProtectedManagementResources(vrfs = setOf("MGMT")), ManagementMutation(vrfOrRoutingInstances = setOf("MGMT"))),
            ProtectedCase(
                ProtectedManagementResources(collectorSourcePaths = setOf("collector/site-a/uplink0")),
                ManagementMutation(collectorSourcePaths = setOf("collector/site-a/uplink0")),
            ),
            ProtectedCase(
                ProtectedManagementResources(requiredOutOfBandRoutes = available),
                ManagementMutation(changedOutOfBandRoutes = available, availableOutOfBandRoutes = available),
            ),
            ProtectedCase(
                ProtectedManagementResources(requiredOutOfBandRoutes = available),
                ManagementMutation(availableOutOfBandRoutes = emptySet()),
            ),
        )

        cases.forEach { case ->
            val fixture = fixture(EvidenceCase(resources = case.resources, mutation = case.mutation, vlanId = case.vlanId))
            val decision = asTenant(fixture.tenantId) {
                gate.evaluate(fixture.plan, ExecutionMode.PRODUCTION_AUTO_APPLY)
            }
            assertThat(decision.allowed).describedAs(case.toString()).isFalse()
            assertThat(decision.code).describedAs(case.toString()).isEqualTo(PolicyCode.PROTECTED_MANAGEMENT_RESOURCE)
        }
    }

    private fun fixture(case: EvidenceCase): Fixture {
        val tenantId = tenantApi.ensureTenant("gate-${UUID.randomUUID()}", "gate").id
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val now = Instant.now()
        val managementEvidenceId = UuidV7.generate()
        asTenant(tenantId) {
            val collectorId = insertCollector(tenantId)
            val capabilityEvidenceId = if (case.capability) {
                insertCapability(tenantId, collectorId, device, case, now)
            } else {
                null
            }
            if (capabilityEvidenceId != null) insertCertification(tenantId, device, capabilityEvidenceId, case, now)
            insertTopologySource(tenantId, device, managementEvidenceId, now)
            insertManagement(tenantId, device, managementEvidenceId, case.resources, case.mutation, now)
        }
        val step = ProvisionStep.create(
            1,
            device,
            ProvisionOperation.ENSURE_PPPOE_TERMINATION,
            mapOf(
                "vlanId" to case.vlanId.toString(),
                SafetyPlanAttributes.VENDOR to "MIKROTIK",
                SafetyPlanAttributes.MODEL to "CCR2004",
                SafetyPlanAttributes.FIRMWARE to "7.20.2",
                SafetyPlanAttributes.TRANSPORT to "HTTPS_REST",
                SafetyPlanAttributes.MANAGEMENT_COMPLETE to "true",
                SafetyPlanAttributes.MANAGEMENT_SOURCE_ID to managementEvidenceId.toString(),
                SafetyPlanAttributes.MANAGEMENT_SOURCE_TYPE to "TOPOLOGY_OBSERVATION",
                SafetyPlanAttributes.INTERFACE_ROLES to case.mutation.interfaceRoles.joinToString(","),
                SafetyPlanAttributes.IP_ADDRESSES to case.mutation.ipAddresses.joinToString(","),
                SafetyPlanAttributes.VRFS to case.mutation.vrfOrRoutingInstances.joinToString(","),
                SafetyPlanAttributes.COLLECTOR_PATHS to case.mutation.collectorSourcePaths.joinToString(","),
                SafetyPlanAttributes.REQUIRED_OOB_ROUTES to case.mutation.requiredOutOfBandRoutes.joinToString(","),
                SafetyPlanAttributes.CHANGED_OOB_ROUTES to case.mutation.changedOutOfBandRoutes.joinToString(","),
                SafetyPlanAttributes.AVAILABLE_OOB_ROUTES to case.mutation.availableOutOfBandRoutes.joinToString(","),
            ),
        )
        return Fixture(tenantId, ProvisionPlan.generate(tenantId, UuidV7.generate(), 1, listOf(step)))
    }

    private fun insertCapability(
        tenantId: UUID,
        collectorId: UUID,
        device: DeviceReference,
        case: EvidenceCase,
        now: Instant,
    ): UUID {
        val reportId = UuidV7.generate()
        val evidenceId = UuidV7.generate()
        val observedAt = if (case.stale) now.minusSeconds(600) else now.minusSeconds(10)
        val expiresAt = if (case.stale) now.minusSeconds(300) else now.plusSeconds(300)
        em.createNativeQuery(
            """INSERT INTO provisioning_collector_device_report
               (id, tenant_id, collector_id, report_key, target_id, vendor, model, firmware, transport,
                capabilities, operation_classes, reported_at, expires_at)
               VALUES (:id, :tenant, :collector, :key, :target, :vendor, 'CCR2004', '7.20.2', 'HTTPS_REST',
                'PPPOE_TERMINATION', 'ENSURE_PPPOE_TERMINATION', :observed, :expires)""",
        ).setParameter("id", reportId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("key", "${device.id}@$observedAt").setParameter("target", device.id.toString())
            .setParameter("vendor", case.vendor).setParameter("observed", observedAt).setParameter("expires", expiresAt)
            .executeUpdate()
        em.createNativeQuery(
            """INSERT INTO provisioning_capability_evidence
               (id, tenant_id, collector_id, report_id, device_kind, device_id, vendor, model, firmware,
                transport, operation_class, supported, observed_at, expires_at)
               VALUES (:id, :tenant, :collector, :report, 'BRAS', :device, :vendor, 'CCR2004', '7.20.2',
                'HTTPS_REST', 'ENSURE_PPPOE_TERMINATION', true, :observed, :expires)""",
        ).setParameter("id", evidenceId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("report", reportId).setParameter("device", device.id).setParameter("vendor", case.vendor)
            .setParameter("observed", observedAt).setParameter("expires", expiresAt).executeUpdate()
        return evidenceId
    }

    private fun insertCertification(
        tenantId: UUID,
        device: DeviceReference,
        evidenceId: UUID,
        case: EvidenceCase,
        now: Instant,
    ) {
        val validUntil = if (case.stale) now.minusSeconds(1) else now.plusSeconds(300)
        em.createNativeQuery(
            """INSERT INTO provisioning_adapter_certification
               (id, tenant_id, device_kind, device_id, vendor, model, firmware, transport, operation_class,
                status, valid_until, evidence_id, certified_by, certified_at)
               VALUES (:id, :tenant, 'BRAS', :device, :vendor, 'CCR2004', '7.20.2', 'HTTPS_REST',
                'ENSURE_PPPOE_TERMINATION', :status, :valid, :evidence, :actor, :certified)""",
        ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId).setParameter("device", device.id)
            .setParameter("vendor", case.vendor)
            .setParameter("status", case.status.name).setParameter("valid", validUntil)
            .setParameter("evidence", evidenceId).setParameter("actor", UuidV7.generate())
            .setParameter("certified", now.minusSeconds(20)).executeUpdate()
    }

    private fun insertManagement(
        tenantId: UUID,
        device: DeviceReference,
        evidenceId: UUID,
        resources: ProtectedManagementResources,
        mutation: ManagementMutation,
        now: Instant,
    ) {
        em.createNativeQuery(
            """INSERT INTO provisioning_management_safety_evidence
               (id, tenant_id, device_kind, device_id, protected_vlan_ranges, protected_ip_prefixes,
                protected_vrfs, protected_interface_roles, protected_collector_paths, protected_oob_routes,
                available_oob_routes, observed_at, valid_until, complete, source_type,
                topology_source_id, device_observation_source_id)
               VALUES (:id, :tenant, 'BRAS', :device, :vlans, :ips, :vrfs, :roles, :paths, :oob,
                :availableOob, :observed, :valid, true, 'TOPOLOGY_OBSERVATION', :source, NULL)""",
        ).setParameter("id", evidenceId).setParameter("tenant", tenantId).setParameter("device", device.id)
            .setParameter("vlans", resources.vlanRanges.joinToString("\n") { "${it.start}-${it.endInclusive}" })
            .setParameter("ips", resources.managementIpPrefixes.joinToString("\n"))
            .setParameter("vrfs", resources.vrfs.joinToString("\n"))
            .setParameter("roles", resources.managementInterfaceRoles.joinToString("\n"))
            .setParameter("paths", resources.collectorSourcePaths.joinToString("\n"))
            .setParameter("oob", resources.requiredOutOfBandRoutes.joinToString("\n"))
            .setParameter("availableOob", mutation.availableOutOfBandRoutes.joinToString("\n"))
            .setParameter("observed", now.minusSeconds(10)).setParameter("valid", now.plusSeconds(300))
            .setParameter("source", evidenceId).executeUpdate()
    }

    private fun insertTopologySource(tenantId: UUID, device: DeviceReference, sourceId: UUID, now: Instant) {
        em.createNativeQuery(
            """INSERT INTO provisioning_managed_node
               (id, tenant_id, name, role, reference_kind, reference_id, administrative_status, observed_at)
               VALUES (:id, :tenant, :name, 'BRAS', 'NAS', :device, 'ENABLED', :observed)""",
        ).setParameter("id", sourceId).setParameter("tenant", tenantId)
            .setParameter("name", "node-${UUID.randomUUID()}").setParameter("device", device.id)
            .setParameter("observed", now.minusSeconds(10)).executeUpdate()
    }

    private fun insertCollector(tenantId: UUID): UUID = UuidV7.generate().also { id ->
        em.createNativeQuery(
            """INSERT INTO collector
               (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
               VALUES (:id, :tenant, :name, :hash, 'gate', 'ACTIVE', 60)""",
        ).setParameter("id", id).setParameter("tenant", tenantId).setParameter("name", "collector-${UUID.randomUUID()}")
            .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2)).executeUpdate()
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private data class Fixture(val tenantId: UUID, val plan: ProvisionPlan)
    private data class ProtectedCase(
        val resources: ProtectedManagementResources,
        val mutation: ManagementMutation,
        val vlanId: Int = 320,
    )
    private data class EvidenceCase(
        val capability: Boolean = true,
        val status: CertificationStatus = CertificationStatus.CERTIFIED,
        val vendor: String = "MIKROTIK",
        val stale: Boolean = false,
        val resources: ProtectedManagementResources = ProtectedManagementResources(managementInterfaceRoles = emptySet()),
        val mutation: ManagementMutation = ManagementMutation(),
        val vlanId: Int = 320,
        val expected: PolicyCode = PolicyCode.AUTO_APPLY_ALLOWED,
    )
}
