package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.integration.CollectorProvisioningChannel
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint as WireFingerprint
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.provisioning.application.port.outbound.AdapterCertificationRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
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
class ProvisioningSafetyEvidencePersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var safetyEvidence: ProvisioningSafetyEvidenceRepository
    @Autowired private lateinit var certifications: AdapterCertificationRepository
    @Autowired private lateinit var collectorChannel: CollectorProvisioningChannel
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `owned fresh collector report creates exact operation evidence while stale and unowned reports are rejected`() {
        val tenantId = tenant("owned-capability")
        val collectorId = asTenant(tenantId) { insertCollector(tenantId) }
        val deviceId = UuidV7.generate()
        val now = Instant.now()
        val target = ProvisioningTarget(deviceId.toString(), "BRAS", "router.invalid", "HTTPS_REST")
        val fresh = DeviceCapabilityReport(
            deviceId.toString(),
            WireFingerprint("MIKROTIK", "CCR2004", "7.20.2", "HTTPS_REST"),
            setOf("PPPOE_TERMINATION"),
            now.minusSeconds(10),
            setOf("ENSURE_PPPOE_TERMINATION"),
        )
        val stale = fresh.copy(reportedAt = now.minusSeconds(3600))
        val unowned = fresh.copy(targetId = UuidV7.generate().toString())

        val acknowledgement = asTenant(tenantId) {
            collectorChannel.accept(
                collectorId,
                tenantId,
                mapOf(target.deviceId to target),
                emptyList(),
                listOf(fresh, stale, unowned),
            )
        }
        val fingerprint = DeviceFingerprint(
            DeviceReference(DeviceKind.BRAS, deviceId),
            "MIKROTIK",
            "CCR2004",
            "7.20.2",
            "HTTPS_REST",
            "ENSURE_PPPOE_TERMINATION",
        )
        val conflictingReplay = fresh.copy(
            fingerprint = WireFingerprint("ZTE", "C600", "2.0", "SSH"),
            operationClasses = setOf("REMOVE_PPPOE_TERMINATION"),
        )
        asTenant(tenantId) {
            collectorChannel.accept(
                collectorId,
                tenantId,
                mapOf(target.deviceId to target),
                emptyList(),
                listOf(conflictingReplay),
            )
        }
        val replayFingerprint = DeviceFingerprint(
            DeviceReference(DeviceKind.BRAS, deviceId),
            "ZTE",
            "C600",
            "2.0",
            "SSH",
            "REMOVE_PPPOE_TERMINATION",
        )

        assertThat(acknowledgement.deviceReportKeys).containsExactly("${fresh.targetId}@${fresh.reportedAt}")
        assertThat(asTenant(tenantId) { safetyEvidence.findCapabilityEvidence(tenantId, fingerprint) }).isNotNull
        assertThat(asTenant(tenantId) { safetyEvidence.findCapabilityEvidence(tenantId, replayFingerprint) == null }).isTrue()
        assertThat(asTenant(tenantId) { countReports(tenantId) }).isEqualTo(1)
    }

    @Test
    fun `exact capability and certification evidence round trip under tenant isolation`() {
        val owner = tenant("safety-owner")
        val other = tenant("safety-other")
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val fingerprint = DeviceFingerprint(
            device,
            "MIKROTIK",
            "CCR2004",
            "7.20.2",
            "HTTPS_REST",
            "ENSURE_PPPOE_TERMINATION",
        )
        val reportId = UuidV7.generate()
        val evidenceId = UuidV7.generate()
        val actorId = UuidV7.generate()
        val observedAt = Instant.parse("2026-09-02T12:00:00Z")
        val expiresAt = observedAt.plusSeconds(300)

        asTenant(owner) {
            val collectorId = insertCollector(owner)
            insertCapabilityEvidence(owner, collectorId, reportId, evidenceId, fingerprint, observedAt, expiresAt)
            certifications.save(
                AdapterCertification.certify(
                    owner,
                    device,
                    fingerprint.vendor,
                    fingerprint.model,
                    fingerprint.firmware,
                    fingerprint.transport,
                    fingerprint.operationClass,
                    CertificationStatus.CERTIFIED,
                    expiresAt,
                    evidenceId,
                    actorId,
                    observedAt,
                ),
            )
        }

        val capability = asTenant(owner) { safetyEvidence.findCapabilityEvidence(owner, fingerprint) }
        val certification = asTenant(owner) { safetyEvidence.findCertificationEvidence(owner, fingerprint) }

        assertThat(capability?.id).isEqualTo(evidenceId)
        assertThat(capability?.fingerprint).isEqualTo(fingerprint)
        assertThat(capability?.supported).isTrue()
        assertThat(capability?.expiresAt).isEqualTo(expiresAt)
        assertThat(certification?.fingerprint).isEqualTo(fingerprint)
        assertThat(certification?.status).isEqualTo(CertificationStatus.CERTIFIED)
        assertThat(certification?.validUntil).isEqualTo(expiresAt)
        assertThat(certification?.revoked).isFalse()
        assertThat(asTenant(other) { safetyEvidence.findCapabilityEvidence(other, fingerprint) == null }).isTrue()
        assertThat(asTenant(other) { safetyEvidence.findCertificationEvidence(other, fingerprint) == null }).isTrue()
    }

    @Test
    fun `complete protected management evidence round trips every resource class`() {
        val tenantId = tenant("management-evidence")
        val device = DeviceReference(DeviceKind.SWITCH, UuidV7.generate())
        val evidenceId = UuidV7.generate()
        val observedAt = Instant.parse("2026-09-02T12:00:00Z")

        asTenant(tenantId) {
            em.createNativeQuery(
                """INSERT INTO provisioning_management_safety_evidence
                   (id, tenant_id, device_kind, device_id, protected_vlan_ranges, protected_ip_prefixes,
                    protected_vrfs, protected_interface_roles, protected_collector_paths, protected_oob_routes,
                    mutation_interface_roles, mutation_ip_addresses, mutation_vrfs, mutation_collector_paths,
                    mutation_required_oob_routes, mutation_changed_oob_routes, available_oob_routes,
                    observed_at, valid_until)
                   VALUES (:id, :tenant, 'SWITCH', :device, '99-99
400-410', '10.20.0.0/16', 'MGMT', 'MANAGEMENT
OOB', 'collector/site-a/uplink0', 'oob/site-a', 'TRUNK', '172.16.1.2', 'CUSTOMER',
                    'collector/site-a/customer', 'oob/site-a', '', 'oob/site-a', :observed, :valid)""",
            ).setParameter("id", evidenceId)
                .setParameter("tenant", tenantId)
                .setParameter("device", device.id)
                .setParameter("observed", observedAt)
                .setParameter("valid", observedAt.plusSeconds(300))
                .executeUpdate()
        }

        val evidence = asTenant(tenantId) { safetyEvidence.findManagementEvidence(tenantId, device) }

        assertThat(evidence?.id).isEqualTo(evidenceId)
        assertThat(evidence?.protectedResources?.vlanRanges).extracting<Int> { it.start }.containsExactly(99, 400)
        assertThat(evidence?.protectedResources?.managementIpPrefixes).containsExactly("10.20.0.0/16")
        assertThat(evidence?.protectedResources?.managementInterfaceRoles).containsExactlyInAnyOrder("MANAGEMENT", "OOB")
        assertThat(evidence?.protectedResources?.vrfs).containsExactly("MGMT")
        assertThat(evidence?.protectedResources?.collectorSourcePaths).containsExactly("collector/site-a/uplink0")
        assertThat(evidence?.protectedResources?.requiredOutOfBandRoutes).containsExactly("oob/site-a")
        assertThat(evidence?.mutation?.interfaceRoles).containsExactly("TRUNK")
        assertThat(evidence?.mutation?.ipAddresses).containsExactly("172.16.1.2")
        assertThat(evidence?.mutation?.vrfOrRoutingInstances).containsExactly("CUSTOMER")
        assertThat(evidence?.mutation?.collectorSourcePaths).containsExactly("collector/site-a/customer")
        assertThat(evidence?.mutation?.requiredOutOfBandRoutes).containsExactly("oob/site-a")
        assertThat(evidence?.mutation?.changedOutOfBandRoutes).isEmpty()
        assertThat(evidence?.mutation?.availableOutOfBandRoutes).containsExactly("oob/site-a")
    }

    private fun insertCapabilityEvidence(
        tenantId: UUID,
        collectorId: UUID,
        reportId: UUID,
        evidenceId: UUID,
        fingerprint: DeviceFingerprint,
        observedAt: Instant,
        expiresAt: Instant,
    ) {
        em.createNativeQuery(
            """INSERT INTO provisioning_collector_device_report
               (id, tenant_id, collector_id, report_key, target_id, vendor, model, firmware, transport,
                capabilities, operation_classes, reported_at, expires_at)
               VALUES (:report, :tenant, :collector, :key, :target, :vendor, :model, :firmware, :transport,
                'PPPOE_TERMINATION', :operation, :observed, :expires)""",
        ).setParameter("report", reportId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("key", "${fingerprint.device.id}@$observedAt").setParameter("target", fingerprint.device.id.toString())
            .setParameter("vendor", fingerprint.vendor).setParameter("model", fingerprint.model)
            .setParameter("firmware", fingerprint.firmware).setParameter("transport", fingerprint.transport)
            .setParameter("operation", fingerprint.operationClass).setParameter("observed", observedAt)
            .setParameter("expires", expiresAt).executeUpdate()
        em.createNativeQuery(
            """INSERT INTO provisioning_capability_evidence
               (id, tenant_id, collector_id, report_id, device_kind, device_id, vendor, model, firmware,
                transport, operation_class, supported, observed_at, expires_at)
               VALUES (:id, :tenant, :collector, :report, :kind, :device, :vendor, :model, :firmware,
                :transport, :operation, true, :observed, :expires)""",
        ).setParameter("id", evidenceId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("report", reportId).setParameter("kind", fingerprint.device.kind.name)
            .setParameter("device", fingerprint.device.id).setParameter("vendor", fingerprint.vendor)
            .setParameter("model", fingerprint.model).setParameter("firmware", fingerprint.firmware)
            .setParameter("transport", fingerprint.transport).setParameter("operation", fingerprint.operationClass)
            .setParameter("observed", observedAt).setParameter("expires", expiresAt).executeUpdate()
    }

    private fun insertCollector(tenantId: UUID): UUID = UuidV7.generate().also { collectorId ->
        em.createNativeQuery(
            """INSERT INTO collector
               (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
               VALUES (:id, :tenant, :name, :hash, 'safe', 'ACTIVE', 60)""",
        ).setParameter("id", collectorId).setParameter("tenant", tenantId)
            .setParameter("name", "collector-${UUID.randomUUID()}")
            .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2)).executeUpdate()
    }

    private fun countReports(tenantId: UUID): Long =
        (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_device_report WHERE tenant_id = :tenant")
            .setParameter("tenant", tenantId).singleResult as Number).toLong()

    private fun tenant(prefix: String) = tenantApi.ensureTenant(
        "$prefix-${UUID.randomUUID().toString().take(8)}",
        prefix,
    ).id

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
