package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningDeviceOwnershipRepository
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ManagementMutation
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
@Transactional(readOnly = true)
class ProvisioningSafetyEvidencePersistenceAdapter(
    private val entityManager: EntityManager,
) : ProvisioningSafetyEvidenceRepository, ProvisioningDeviceOwnershipRepository {
    override fun owns(tenantId: UUID, device: DeviceReference): Boolean {
        val count = entityManager.createNativeQuery(
            """SELECT count(*) FROM provisioning_capability_evidence
               WHERE tenant_id = :tenant AND device_kind = :kind AND device_id = :device""",
        ).setParameter("tenant", tenantId).setParameter("kind", device.kind.name).setParameter("device", device.id)
            .singleResult as Number
        return count.toLong() > 0
    }

    override fun findCapabilityEvidence(tenantId: UUID, fingerprint: DeviceFingerprint): CapabilityEvidence? {
        val row = entityManager.createNativeQuery(
            """SELECT id, tenant_id, device_kind, device_id, vendor, model, firmware, transport,
                      operation_class, supported, observed_at, expires_at
               FROM provisioning_capability_evidence
               WHERE tenant_id = :tenant AND device_kind = :kind AND device_id = :device
                 AND operation_class = :operation
               ORDER BY observed_at DESC, id DESC LIMIT 1""",
        ).setParameter("tenant", tenantId).setParameter("kind", fingerprint.device.kind.name)
            .setParameter("device", fingerprint.device.id).setParameter("operation", fingerprint.operationClass)
            .resultList.singleOrNull() as? Array<*> ?: return null
        return CapabilityEvidence(
            row[0] as UUID,
            row[1] as UUID,
            row.fingerprint(),
            row[9] as Boolean,
            row[10] as Instant,
            row[11] as Instant,
        )
    }

    override fun findCertificationEvidence(tenantId: UUID, fingerprint: DeviceFingerprint): CertificationEvidence? {
        val row = entityManager.createNativeQuery(
            """SELECT id, tenant_id, device_kind, device_id, vendor, model, firmware, transport,
                      operation_class, status, valid_until, revoked_at
               FROM provisioning_adapter_certification
               WHERE tenant_id = :tenant AND device_kind = :kind AND device_id = :device
                 AND operation_class = :operation
               ORDER BY certified_at DESC, id DESC LIMIT 1""",
        ).setParameter("tenant", tenantId).setParameter("kind", fingerprint.device.kind.name)
            .setParameter("device", fingerprint.device.id).setParameter("operation", fingerprint.operationClass)
            .resultList.singleOrNull() as? Array<*> ?: return null
        return CertificationEvidence(
            row[0] as UUID,
            row[1] as UUID,
            row.fingerprint(),
            CertificationStatus.valueOf(row[9] as String),
            row[10] as Instant,
            row[11] != null,
        )
    }

    override fun findManagementEvidence(tenantId: UUID, device: DeviceReference): ManagementSafetyEvidence? {
        val row = entityManager.createNativeQuery(
            """SELECT id, tenant_id, device_kind, device_id, protected_vlan_ranges, protected_ip_prefixes,
                      protected_vrfs, protected_interface_roles, protected_collector_paths, protected_oob_routes,
                      mutation_interface_roles, mutation_ip_addresses, mutation_vrfs, mutation_collector_paths,
                      mutation_required_oob_routes, mutation_changed_oob_routes, available_oob_routes,
                      observed_at, valid_until
               FROM provisioning_management_safety_evidence
               WHERE tenant_id = :tenant AND device_kind = :kind AND device_id = :device""",
        ).setParameter("tenant", tenantId).setParameter("kind", device.kind.name).setParameter("device", device.id)
            .resultList.singleOrNull() as? Array<*> ?: return null
        return ManagementSafetyEvidence(
            row[0] as UUID,
            row[1] as UUID,
            DeviceReference(DeviceKind.valueOf(row[2] as String), row[3] as UUID),
            ProtectedManagementResources(
                vlanRanges = lines(row[4]).map(::range),
                managementIpPrefixes = lines(row[5]),
                vrfs = lines(row[6]),
                managementInterfaceRoles = lines(row[7]),
                collectorSourcePaths = lines(row[8]),
                requiredOutOfBandRoutes = lines(row[9]),
            ),
            ManagementMutation(
                interfaceRoles = lines(row[10]),
                ipAddresses = lines(row[11]),
                vrfOrRoutingInstances = lines(row[12]),
                collectorSourcePaths = lines(row[13]),
                requiredOutOfBandRoutes = lines(row[14]),
                changedOutOfBandRoutes = lines(row[15]),
                availableOutOfBandRoutes = lines(row[16]),
            ),
            row[17] as Instant,
            row[18] as Instant,
        )
    }

    private fun Array<*>.fingerprint() = DeviceFingerprint(
        DeviceReference(DeviceKind.valueOf(this[2] as String), this[3] as UUID),
        this[4] as String,
        this[5] as String,
        this[6] as String,
        this[7] as String,
        this[8] as String,
    )

    private fun lines(value: Any?): Set<String> = (value as String).lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(linkedSetOf())

    private fun range(value: String): VlanRange {
        val parts = value.split('-', limit = 2)
        return VlanRange(parts[0].toInt(), parts[1].toInt())
    }
}
