package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationEvidence
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import java.util.UUID

interface ProvisioningSafetyEvidenceRepository {
    fun findCapabilityEvidence(tenantId: UUID, fingerprint: DeviceFingerprint): CapabilityEvidence?
    fun findCertificationEvidence(tenantId: UUID, fingerprint: DeviceFingerprint): CertificationEvidence?
    fun findManagementEvidence(tenantId: UUID, device: DeviceReference): ManagementSafetyEvidence?
}

fun interface ProvisioningDeviceOwnershipRepository {
    fun owns(tenantId: UUID, device: DeviceReference): Boolean
}

interface ProvisioningManagementEvidenceRepository {
    fun sourceExists(
        tenantId: UUID,
        device: DeviceReference,
        sourceType: ManagementEvidenceSourceType,
        sourceEvidenceId: UUID,
    ): Boolean

    fun save(value: ManagementSafetyEvidence): ManagementSafetyEvidence
}
