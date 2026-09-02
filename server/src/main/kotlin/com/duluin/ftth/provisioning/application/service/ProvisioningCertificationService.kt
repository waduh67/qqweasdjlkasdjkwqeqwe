package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.AdapterCertificationRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningDeviceOwnershipRepository
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
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
    val status: CertificationStatus,
    val validUntil: Instant,
    val evidenceId: UUID,
)

@Service
class ProvisioningCertificationService(
    private val currentUser: CurrentUserProvider,
    private val certifications: AdapterCertificationRepository,
    private val ownership: ProvisioningDeviceOwnershipRepository,
    private val clock: Clock,
) {
    fun certify(command: CertifyAdapterCommand): AdapterCertification {
        val actor = platformActor()
        return TenantContext.runAs(command.targetTenantId) {
            if (!ownership.owns(command.targetTenantId, command.device)) {
                throw AccessDeniedException("CERTIFICATION_TARGET_NOT_OWNED")
            }
            certifications.save(
                AdapterCertification.certify(
                    command.targetTenantId,
                    command.device,
                    command.vendor,
                    command.model,
                    command.firmware,
                    command.transport,
                    command.operationClass,
                    command.status,
                    command.validUntil,
                    command.evidenceId,
                    actor.userId,
                    clock.instant(),
                ),
            )
        }
    }

    fun revoke(targetTenantId: UUID, certificationId: UUID): AdapterCertification {
        val actor = platformActor()
        return TenantContext.runAs(targetTenantId) {
            val certification = certifications.findById(certificationId)
                ?: throw NotFoundException("CERTIFICATION_NOT_FOUND")
            if (certification.tenantId != targetTenantId || !ownership.owns(targetTenantId, certification.device)) {
                throw AccessDeniedException("CERTIFICATION_TARGET_NOT_OWNED")
            }
            certification.revoke(actor.userId, clock.instant())
            certifications.save(certification)
        }
    }

    private fun platformActor(): AuthenticatedUser = currentUser.currentOrNull()
        ?.takeIf(AuthenticatedUser::platformAdmin)
        ?: throw AccessDeniedException("PLATFORM_ADMIN_REQUIRED")
}
