package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningManagementEvidenceRepository
import com.duluin.ftth.provisioning.application.service.ProvisioningManagementEvidenceService
import com.duluin.ftth.provisioning.application.service.ProvisioningAuditPublisher
import com.duluin.ftth.provisioning.application.service.RecordManagementEvidenceCommand
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.UUID

class ProvisioningManagementEvidenceServiceTest {
    private val tenantId = UuidV7.generate()
    private val device = DeviceReference(DeviceKind.SWITCH, UuidV7.generate())
    private val sourceId = UuidV7.generate()
    private val observedAt = Instant.parse("2026-09-02T12:00:00Z")

    @Test
    fun `server records complete protection evidence only from an owned source`() {
        val repository = RecordingRepository(sourceOwned = true)
        val service = service(repository)

        val evidence = service.record(command())

        assertThat(evidence.complete).isTrue()
        assertThat(evidence.sourceType).isEqualTo(ManagementEvidenceSourceType.TOPOLOGY_OBSERVATION)
        assertThat(evidence.sourceEvidenceId).isEqualTo(sourceId)
        assertThat(repository.saved).containsExactly(evidence)
    }

    @Test
    fun `server rejects management evidence whose source is absent or foreign`() {
        val repository = RecordingRepository(sourceOwned = false)

        assertThatThrownBy { service(repository).record(command()) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage("MANAGEMENT_EVIDENCE_SOURCE_INVALID")
        assertThat(repository.saved).isEmpty()
    }

    private fun service(repository: RecordingRepository): ProvisioningManagementEvidenceService {
        val actor = AuthenticatedUser(
            UuidV7.generate(), tenantId, "platform@example.test", "Platform", true, emptySet(), emptySet(),
        )
        val currentUser = object : CurrentUserProvider { override fun currentOrNull() = actor }
        return ProvisioningManagementEvidenceService(
            repository, currentUser, ProvisioningAuditPublisher(currentUser, ApplicationEventPublisher { }),
        )
    }

    @Test
    fun `tenant permission cannot configure protected management resources`() {
        val repository = RecordingRepository(sourceOwned = true)
        val tenantActor = AuthenticatedUser(
            UuidV7.generate(), tenantId, "tenant@example.test", "Tenant", false,
            setOf("provisioning.segment.manage"), emptySet(),
        )
        val currentUser = object : CurrentUserProvider { override fun currentOrNull() = tenantActor }
        val service = ProvisioningManagementEvidenceService(
            repository, currentUser, ProvisioningAuditPublisher(currentUser, ApplicationEventPublisher { }),
        )

        assertThatThrownBy { service.record(command()) }
            .isInstanceOf(AccessDeniedException::class.java)
            .hasMessage("PLATFORM_ADMIN_REQUIRED")
        assertThat(repository.saved).isEmpty()
    }

    private fun command() = RecordManagementEvidenceCommand(
        tenantId,
        device,
        ProtectedManagementResources(
            vlanRanges = listOf(VlanRange(99, 99)),
            managementIpPrefixes = setOf("10.20.0.0/16"),
            vrfs = setOf("MGMT"),
            managementInterfaceRoles = setOf("MANAGEMENT"),
            collectorSourcePaths = setOf("collector/site-a/uplink0"),
            requiredOutOfBandRoutes = setOf("oob/site-a"),
        ),
        setOf("oob/site-a"),
        ManagementEvidenceSourceType.TOPOLOGY_OBSERVATION,
        sourceId,
        observedAt,
        observedAt.plusSeconds(300),
    )

    private class RecordingRepository(private val sourceOwned: Boolean) : ProvisioningManagementEvidenceRepository {
        val saved = mutableListOf<ManagementSafetyEvidence>()
        override fun sourceExists(
            tenantId: UUID,
            device: DeviceReference,
            sourceType: ManagementEvidenceSourceType,
            sourceEvidenceId: UUID,
        ) = sourceOwned

        override fun save(value: ManagementSafetyEvidence): ManagementSafetyEvidence = value.also(saved::add)
    }
}
