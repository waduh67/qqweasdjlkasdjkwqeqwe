package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.application.service.EvidenceBackedProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.SafetyPlanAttributes
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ManagementMutation
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProvisioningProductionSafetyGateTest {
    private val tenantId = UuidV7.generate()
    private val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
    private val now = Instant.parse("2026-09-02T12:00:00Z")
    private val fingerprint = DeviceFingerprint(
        device,
        "MIKROTIK",
        "CCR2004",
        "7.20.2",
        "HTTPS_REST",
        ProvisionOperation.ENSURE_PPPOE_TERMINATION.name,
    )

    @Test
    fun `production gate allows exact fresh certified safe plan and returns evidence ids`() {
        val evidence = EvidenceFixture(fingerprint)

        val decision = gate(evidence).evaluate(plan(), ExecutionMode.PRODUCTION_AUTO_APPLY)

        assertThat(decision.allowed).isTrue()
        assertThat(decision.code).isEqualTo(PolicyCode.AUTO_APPLY_ALLOWED)
        assertThat(decision.evidenceIds).containsExactlyInAnyOrder(
            evidence.capability.id,
            evidence.certification.id,
            evidence.management.id,
        )
    }

    @Test
    fun `production gate rejects every capability and certification blocker`() {
        val cases = listOf(
            EvidenceFixture(fingerprint, capabilityPresent = false) to PolicyCode.MISSING_CAPABILITY_EVIDENCE,
            EvidenceFixture(fingerprint, status = CertificationStatus.PROVISIONAL) to PolicyCode.UNCERTIFIED_CAPABILITY,
            EvidenceFixture(fingerprint, supported = false) to PolicyCode.UNSUPPORTED_CAPABILITY,
            EvidenceFixture(fingerprint, capabilityExpiresAt = now) to PolicyCode.STALE_CAPABILITY_EVIDENCE,
            EvidenceFixture(fingerprint, certificationExpiresAt = now) to PolicyCode.STALE_CERTIFICATION_EVIDENCE,
            EvidenceFixture(fingerprint, revoked = true) to PolicyCode.UNCERTIFIED_CAPABILITY,
            EvidenceFixture(fingerprint, returnedFingerprint = fingerprint.copy(firmware = "7.21")) to PolicyCode.FINGERPRINT_MISMATCH,
        )

        cases.forEach { (evidence, code) ->
            assertRejected(gate(evidence), code)
        }
    }

    @Test
    fun `production gate rejects every protected management mutation class`() {
        val protected = ProtectedManagementResources(
            vlanRanges = listOf(VlanRange(99, 99)),
            managementInterfaceRoles = setOf("MANAGEMENT"),
            managementIpPrefixes = setOf("10.20.0.0/16"),
            vrfs = setOf("MGMT"),
            collectorSourcePaths = setOf("collector/site-a/uplink0"),
            requiredOutOfBandRoutes = setOf("oob/site-a"),
        )
        val available = setOf("oob/site-a")
        val mutations = listOf(
            ManagementMutation(vlanIds = setOf(99), availableOutOfBandRoutes = available),
            ManagementMutation(interfaceRoles = setOf("MANAGEMENT"), availableOutOfBandRoutes = available),
            ManagementMutation(ipAddresses = setOf("10.20.1.2"), availableOutOfBandRoutes = available),
            ManagementMutation(vrfOrRoutingInstances = setOf("MGMT"), availableOutOfBandRoutes = available),
            ManagementMutation(collectorSourcePaths = setOf("collector/site-a/uplink0"), availableOutOfBandRoutes = available),
            ManagementMutation(changedOutOfBandRoutes = setOf("oob/site-a"), availableOutOfBandRoutes = available),
            ManagementMutation(availableOutOfBandRoutes = emptySet()),
        )

        mutations.forEach { mutation ->
            val evidence = EvidenceFixture(fingerprint, resources = protected, mutation = mutation)
            assertRejected(gate(evidence), PolicyCode.PROTECTED_MANAGEMENT_RESOURCE)
        }
    }

    @Test
    fun `production gate rejects missing stale and cross tenant management evidence`() {
        val cases = listOf(
            EvidenceFixture(fingerprint, managementPresent = false) to PolicyCode.MISSING_MANAGEMENT_EVIDENCE,
            EvidenceFixture(fingerprint, managementValidUntil = now) to PolicyCode.STALE_MANAGEMENT_EVIDENCE,
            EvidenceFixture(fingerprint, managementTenantId = UuidV7.generate()) to PolicyCode.TENANT_SCOPE_MISMATCH,
        )

        cases.forEach { (evidence, code) -> assertRejected(gate(evidence), code) }
    }

    private fun gate(evidence: EvidenceFixture): ProvisioningSafetyGate = EvidenceBackedProvisioningSafetyGate(
        evidence,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun assertRejected(gate: ProvisioningSafetyGate, expected: PolicyCode) {
        assertThatThrownBy { gate.requireAllowed(plan(), ExecutionMode.PRODUCTION_AUTO_APPLY) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage(expected.name)
    }

    private fun plan(): ProvisionPlan {
        val step = ProvisionStep.create(
            1,
            device,
            ProvisionOperation.ENSURE_PPPOE_TERMINATION,
            mapOf(
                "vlanId" to "320",
                SafetyPlanAttributes.VENDOR to fingerprint.vendor,
                SafetyPlanAttributes.MODEL to fingerprint.model,
                SafetyPlanAttributes.FIRMWARE to fingerprint.firmware,
                SafetyPlanAttributes.TRANSPORT to fingerprint.transport,
            ),
        )
        return ProvisionPlan.generate(tenantId, UuidV7.generate(), 1, listOf(step))
    }

    private inner class EvidenceFixture(
        requested: DeviceFingerprint,
        private val capabilityPresent: Boolean = true,
        private val managementPresent: Boolean = true,
        supported: Boolean = true,
        status: CertificationStatus = CertificationStatus.CERTIFIED,
        capabilityExpiresAt: Instant = now.plusSeconds(300),
        certificationExpiresAt: Instant = now.plusSeconds(300),
        managementValidUntil: Instant = now.plusSeconds(300),
        revoked: Boolean = false,
        returnedFingerprint: DeviceFingerprint = requested,
        managementTenantId: UUID = tenantId,
        resources: ProtectedManagementResources = ProtectedManagementResources(requiredOutOfBandRoutes = setOf("oob/site-a")),
        mutation: ManagementMutation = ManagementMutation(
            vlanIds = setOf(320),
            interfaceRoles = setOf("CUSTOMER"),
            availableOutOfBandRoutes = setOf("oob/site-a"),
        ),
    ) : ProvisioningSafetyEvidenceRepository {
        val capability = CapabilityEvidence(
            UuidV7.generate(), tenantId, returnedFingerprint, supported, now.minusSeconds(30), capabilityExpiresAt,
        )
        val certification = CertificationEvidence(
            UuidV7.generate(), tenantId, returnedFingerprint, status, certificationExpiresAt, revoked,
        )
        val management = ManagementSafetyEvidence(
            UuidV7.generate(), managementTenantId, device, resources, mutation, now.minusSeconds(30), managementValidUntil,
        )

        override fun findCapabilityEvidence(tenantId: UUID, fingerprint: DeviceFingerprint) =
            capability.takeIf { capabilityPresent }

        override fun findCertificationEvidence(tenantId: UUID, fingerprint: DeviceFingerprint) = certification

        override fun findManagementEvidence(tenantId: UUID, device: DeviceReference) =
            management.takeIf { managementPresent }
    }
}
