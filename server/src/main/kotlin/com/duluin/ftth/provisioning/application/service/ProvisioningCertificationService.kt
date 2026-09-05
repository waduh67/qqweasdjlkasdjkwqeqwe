package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.AdapterCertificationRepository
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningCertificationUseCase
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningDeviceOwnershipRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ProvisioningCapabilityPolicy
import com.duluin.ftth.provisioning.domain.policy.StepCapabilityRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CertifyAdapterCommand(
    val targetTenantId: UUID,
    val device: DeviceReference,
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val operationClass: String,
    val validUntil: Instant,
)

@Service
class ProvisioningCertificationService(
    private val currentUser: CurrentUserProvider,
    private val certifications: AdapterCertificationRepository,
    private val ownership: ProvisioningDeviceOwnershipRepository,
    private val safetyEvidence: ProvisioningSafetyEvidenceRepository,
    private val clock: Clock,
    private val audit: ProvisioningAuditPublisher,
) : ProvisioningCertificationUseCase {
    override fun list(targetTenantId: UUID): List<AdapterCertification> {
        platformActor()
        return TenantContext.runAs(targetTenantId) { certifications.findAll() }
    }

    override fun certify(command: CertifyAdapterCommand): AdapterCertification {
        val actor = platformActor()
        val saved = TenantContext.runAs(command.targetTenantId) {
            if (!ownership.owns(command.targetTenantId, command.device)) {
                throw AccessDeniedException("CERTIFICATION_TARGET_NOT_OWNED")
            }
            val fingerprint = DeviceFingerprint(
                command.device,
                command.vendor,
                command.model,
                command.firmware,
                command.transport,
                command.operationClass,
            )
            val capability = safetyEvidence.findCapabilityEvidence(command.targetTenantId, fingerprint)
            val capabilityDecision = ProvisioningCapabilityPolicy(clock.instant())
                .evaluate(
                    listOf(
                        StepCapabilityRequest(
                            command.targetTenantId,
                            fingerprint,
                            capability,
                            null,
                        ),
                    ),
                    ExecutionMode.DRY_RUN,
                )
            if (!capabilityDecision.allowed) throw ValidationException(capabilityDecision.code.name)
            certifications.save(
                AdapterCertification.certify(
                    command.targetTenantId,
                    command.device,
                    command.vendor,
                    command.model,
                    command.firmware,
                    command.transport,
                    command.operationClass,
                    CertificationStatus.CERTIFIED,
                    command.validUntil,
                    requireNotNull(capability).id,
                    actor.userId,
                    clock.instant(),
                ),
            )
        }
        audit.publish(ProvisioningAuditRecord(
            command.targetTenantId, "provisioning.certification.created", "AdapterCertification", saved.id,
            mapOf("deviceKind" to command.device.kind.name, "deviceId" to command.device.id.toString()),
        ))
        return saved
    }

    override fun revoke(targetTenantId: UUID, certificationId: UUID, revision: Int): AdapterCertification {
        val actor = platformActor()
        val revoked = TenantContext.runAs(targetTenantId) {
            val certification = certifications.findById(certificationId)
                ?: throw NotFoundException("CERTIFICATION_NOT_FOUND")
            if (revision != if (certification.active) 1 else 2) {
                throw com.duluin.ftth.common.domain.error.ConflictException("STALE_REVISION")
            }
            if (certification.tenantId != targetTenantId || !ownership.owns(targetTenantId, certification.device)) {
                throw AccessDeniedException("CERTIFICATION_TARGET_NOT_OWNED")
            }
            certification.revoke(actor.userId, clock.instant())
            certifications.save(certification)
        }
        audit.publish(ProvisioningAuditRecord(targetTenantId, "provisioning.certification.revoked", "AdapterCertification", revoked.id))
        return revoked
    }

    private fun platformActor(): AuthenticatedUser = currentUser.currentOrNull()
        ?.takeIf(AuthenticatedUser::platformAdmin)
        ?: throw AccessDeniedException("PLATFORM_ADMIN_REQUIRED")
}
