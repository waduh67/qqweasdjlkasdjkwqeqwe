package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.application.service.EvidenceBackedProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.SafetyGateScope
import com.duluin.ftth.provisioning.application.service.SafetyPlanAttributes
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationEvidence
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProvisioningStepScopedSafetyGateTest {
    private val now = Instant.parse("2026-09-02T12:00:00Z")
    private val tenantId = UuidV7.generate()
    private val mutatedDevice = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
    private val unrelatedDevice = DeviceReference(DeviceKind.OLT, UuidV7.generate())
    private val sourceId = UuidV7.generate()

    @Test
    fun `rollback authorizes only compensated step when unrelated management evidence is missing`() {
        val mutatedStep = step(1, mutatedDevice, sourceId)
        val unrelatedStep = step(2, unrelatedDevice, UuidV7.generate())
        val plan = ProvisionPlan.generate(tenantId, UuidV7.generate(), 1, listOf(mutatedStep, unrelatedStep))
        val repository = object : ProvisioningSafetyEvidenceRepository {
            override fun findCapabilityEvidence(tenantId: UUID, fingerprint: DeviceFingerprint): CapabilityEvidence? = null
            override fun findCertificationEvidence(tenantId: UUID, fingerprint: DeviceFingerprint): CertificationEvidence? = null
            override fun findManagementEvidence(tenantId: UUID, device: DeviceReference): ManagementSafetyEvidence? =
                if (device == mutatedDevice) {
                    ManagementSafetyEvidence(
                        UuidV7.generate(),
                        tenantId,
                        device,
                        ProtectedManagementResources(managementInterfaceRoles = emptySet()),
                        emptySet(),
                        now.minusSeconds(30),
                        now.plusSeconds(300),
                        true,
                        ManagementEvidenceSourceType.DEVICE_OBSERVATION,
                        sourceId,
                    )
                } else {
                    null
                }
        }
        val gate = EvidenceBackedProvisioningSafetyGate(repository, Clock.fixed(now, ZoneOffset.UTC))

        val decision = gate.requireStepAllowed(
            plan,
            mutatedStep.id,
            ExecutionMode.PRODUCTION_AUTO_APPLY,
            SafetyGateScope.ROLLBACK,
        )

        assertThat(decision.allowed).isTrue()
    }

    private fun step(order: Int, device: DeviceReference, evidenceId: UUID) = ProvisionStep.create(
        order,
        device,
        ProvisionOperation.ENSURE_PPPOE_TERMINATION,
        mapOf(
            "vlanId" to "320",
            SafetyPlanAttributes.VENDOR to "MIKROTIK",
            SafetyPlanAttributes.MODEL to "CCR2004",
            SafetyPlanAttributes.FIRMWARE to "7.20.2",
            SafetyPlanAttributes.TRANSPORT to "HTTPS_REST",
            SafetyPlanAttributes.MANAGEMENT_COMPLETE to "true",
            SafetyPlanAttributes.MANAGEMENT_SOURCE_TYPE to "DEVICE_OBSERVATION",
            SafetyPlanAttributes.MANAGEMENT_SOURCE_ID to evidenceId.toString(),
            SafetyPlanAttributes.INTERFACE_ROLES to "CUSTOMER",
            SafetyPlanAttributes.IP_ADDRESSES to "",
            SafetyPlanAttributes.VRFS to "",
            SafetyPlanAttributes.COLLECTOR_PATHS to "",
            SafetyPlanAttributes.REQUIRED_OOB_ROUTES to "",
            SafetyPlanAttributes.CHANGED_OOB_ROUTES to "",
            SafetyPlanAttributes.AVAILABLE_OOB_ROUTES to "",
        ),
    )
}
