package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.AdapterCertificationRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningDeviceOwnershipRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.application.service.CertifyAdapterCommand
import com.duluin.ftth.provisioning.application.service.ProvisioningCertificationService
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProvisioningCertificationServiceTest {
    private val platformTenantId = UuidV7.generate()
    private val targetTenantId = UuidV7.generate()
    private val actorId = UuidV7.generate()
    private val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
    private val now = Instant.parse("2026-09-02T12:00:00Z")
    private val repository = RecordingCertificationRepository()

    @Test
    fun `tenant actor cannot certify even when permission metadata is present`() {
        val actor = user(platformAdmin = false, tenantId = targetTenantId)
        val service = service(actor)

        assertThatThrownBy { service.certify(command(targetTenantId)) }
            .isInstanceOf(AccessDeniedException::class.java)
            .hasMessage("PLATFORM_ADMIN_REQUIRED")
        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `tenant actor cannot certify another tenant`() {
        val actor = user(platformAdmin = false, tenantId = UuidV7.generate())

        assertThatThrownBy { service(actor).certify(command(targetTenantId)) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `platform actor certifies exact target tenant fingerprint with evidence linkage`() {
        val certification = service(user(platformAdmin = true, tenantId = platformTenantId)).certify(command(targetTenantId))

        assertThat(certification.tenantId).isEqualTo(targetTenantId)
        assertThat(certification.device).isEqualTo(device)
        assertThat(certification.vendor).isEqualTo("MIKROTIK")
        assertThat(certification.status).isEqualTo(CertificationStatus.CERTIFIED)
        assertThat(certification.validUntil).isEqualTo(now.plusSeconds(3600))
        assertThat(certification.evidenceId).isEqualTo(EVIDENCE_ID)
        assertThat(certification.certifiedBy).isEqualTo(actorId)
        assertThat(repository.savedTenantContext).isEqualTo(targetTenantId)
    }

    @Test
    fun `certification evidence identity is resolved from exact current capability`() {
        val certification = service(user(platformAdmin = true, tenantId = platformTenantId)).certify(command(targetTenantId))

        assertThat(certification.evidenceId).isEqualTo(EVIDENCE_ID)
    }

    @Test
    fun `platform actor cannot certify a target not owned by the selected tenant`() {
        val actor = user(platformAdmin = true, tenantId = platformTenantId)

        assertThatThrownBy { service(actor, ownsTarget = false).certify(command(targetTenantId)) }
            .isInstanceOf(AccessDeniedException::class.java)
            .hasMessage("CERTIFICATION_TARGET_NOT_OWNED")
    }

    @Test
    fun `tenant actor cannot revoke certification with permission metadata`() {
        val platformService = service(user(platformAdmin = true, tenantId = platformTenantId))
        val certification = platformService.certify(command(targetTenantId))

        assertThatThrownBy {
            service(user(platformAdmin = false, tenantId = targetTenantId)).revoke(targetTenantId, certification.id)
        }.isInstanceOf(AccessDeniedException::class.java)
            .hasMessage("PLATFORM_ADMIN_REQUIRED")
        assertThat(certification.revokedAt).isNull()
    }

    @Test
    fun `platform actor revokes target tenant certification with actor provenance`() {
        val platformService = service(user(platformAdmin = true, tenantId = platformTenantId))
        val certification = platformService.certify(command(targetTenantId))

        val revoked = platformService.revoke(targetTenantId, certification.id)

        assertThat(revoked.revokedAt).isEqualTo(now)
        assertThat(revoked.revokedBy).isEqualTo(actorId)
        assertThat(repository.savedTenantContext).isEqualTo(targetTenantId)
    }

    @Test
    fun `certification resolves and rejects stale unsupported or mismatched capability evidence`() {
        val actor = user(platformAdmin = true, tenantId = platformTenantId)
        val cases = listOf(
            Triple(now, true, fingerprint()) to "STALE_CAPABILITY_EVIDENCE",
            Triple(now.plusSeconds(300), false, fingerprint()) to "UNSUPPORTED_CAPABILITY",
            Triple(now.plusSeconds(300), true, fingerprint().copy(firmware = "7.21")) to "FINGERPRINT_MISMATCH",
        )

        cases.forEach { (evidenceCase, expected) ->
            assertThatThrownBy {
                service(
                    actor,
                    capabilityExpiresAt = evidenceCase.first,
                    capabilitySupported = evidenceCase.second,
                    returnedFingerprint = evidenceCase.third,
                ).certify(command(targetTenantId))
            }.isInstanceOf(ValidationException::class.java).hasMessage(expected)
        }
    }

    private fun service(
        actor: AuthenticatedUser,
        ownsTarget: Boolean = true,
        capabilityExpiresAt: Instant = now.plusSeconds(300),
        capabilitySupported: Boolean = true,
        returnedFingerprint: DeviceFingerprint = fingerprint(),
    ) = ProvisioningCertificationService(
        object : CurrentUserProvider {
            override fun currentOrNull() = actor
        },
        repository,
        ProvisioningDeviceOwnershipRepository { _, _ -> ownsTarget },
        evidenceRepository(capabilityExpiresAt, capabilitySupported, returnedFingerprint),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun user(platformAdmin: Boolean, tenantId: UUID) = AuthenticatedUser(
        actorId,
        tenantId,
        "actor@example.test",
        "Actor",
        platformAdmin,
        setOf("provisioning.certification.manage"),
        emptySet(),
    )

    private fun command(tenantId: UUID) = CertifyAdapterCommand(
        targetTenantId = tenantId,
        device = device,
        vendor = "MIKROTIK",
        model = "CCR2004",
        firmware = "7.20.2",
        transport = "HTTPS_REST",
        operationClass = "ENSURE_PPPOE_TERMINATION",
        validUntil = now.plusSeconds(3600),
    )

    private fun evidenceRepository(
        expiresAt: Instant,
        supported: Boolean,
        returnedFingerprint: DeviceFingerprint,
    ) = object : ProvisioningSafetyEvidenceRepository {
        override fun findCapabilityEvidence(tenantId: UUID, fingerprint: DeviceFingerprint) = CapabilityEvidence(
            EVIDENCE_ID,
            tenantId,
            returnedFingerprint,
            supported,
            now.minusSeconds(30),
            expiresAt,
        )

        override fun findCertificationEvidence(tenantId: UUID, fingerprint: DeviceFingerprint) = null
        override fun findManagementEvidence(tenantId: UUID, device: DeviceReference): ManagementSafetyEvidence? = null
    }

    private fun fingerprint() = DeviceFingerprint(
        device,
        "MIKROTIK",
        "CCR2004",
        "7.20.2",
        "HTTPS_REST",
        "ENSURE_PPPOE_TERMINATION",
    )

    private class RecordingCertificationRepository : AdapterCertificationRepository {
        val saved = mutableListOf<AdapterCertification>()
        var savedTenantContext: UUID? = null

        override fun save(value: AdapterCertification): AdapterCertification = value.also {
            saved += it
            savedTenantContext = TenantContext.tenantId()
        }

        override fun findById(id: UUID): AdapterCertification? = saved.firstOrNull { it.id == id }
    }

    private companion object {
        val EVIDENCE_ID: UUID = UUID.fromString("0199386e-9718-7000-8000-000000000999")
    }
}
