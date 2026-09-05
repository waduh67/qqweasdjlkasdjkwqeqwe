package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningManagementEvidenceRepository
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class RecordManagementEvidenceCommand(
    val tenantId: UUID,
    val device: DeviceReference,
    val protectedResources: ProtectedManagementResources,
    val availableOutOfBandRoutes: Set<String>,
    val sourceType: ManagementEvidenceSourceType,
    val sourceEvidenceId: UUID,
    val observedAt: Instant,
    val validUntil: Instant,
)

@Service
class ProvisioningManagementEvidenceService(
    private val evidence: ProvisioningManagementEvidenceRepository,
    private val currentUser: CurrentUserProvider,
    private val audit: ProvisioningAuditPublisher,
    private val revisions: ProvisioningResourceRevisionStore? = null,
) {
    fun record(command: RecordManagementEvidenceCommand, expectedRevision: Int = 1): ManagementSafetyEvidence {
        val actor = currentUser.currentOrNull()?.takeIf { it.platformAdmin }
            ?: throw AccessDeniedException("PLATFORM_ADMIN_REQUIRED")
        val saved = TenantContext.runAs(command.tenantId) {
        revisions?.advance("MANAGEMENT_PROTECTION", command.device.id, expectedRevision)
        if (!command.validUntil.isAfter(command.observedAt)) throw ValidationException("MANAGEMENT_EVIDENCE_VALIDITY_INVALID")
        if (!evidence.sourceExists(command.tenantId, command.device, command.sourceType, command.sourceEvidenceId)) {
            throw ValidationException("MANAGEMENT_EVIDENCE_SOURCE_INVALID")
        }
        evidence.save(
            ManagementSafetyEvidence(
                UuidV7.generate(),
                command.tenantId,
                command.device,
                command.protectedResources,
                command.availableOutOfBandRoutes,
                command.observedAt,
                command.validUntil,
                complete = true,
                sourceType = command.sourceType,
                sourceEvidenceId = command.sourceEvidenceId,
            ),
        )
        }
        audit.publish(ProvisioningAuditRecord(
            command.tenantId, "provisioning.management-protection.configured", "ManagementSafetyEvidence", saved.id,
            mapOf("deviceKind" to command.device.kind.name, "deviceId" to command.device.id.toString()),
        ))
        return saved
    }
}
