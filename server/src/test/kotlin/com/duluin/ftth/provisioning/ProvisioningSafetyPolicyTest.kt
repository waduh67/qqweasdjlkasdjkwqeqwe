package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ManagementMutation
import com.duluin.ftth.provisioning.domain.policy.PolicyActor
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementPolicy
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import com.duluin.ftth.provisioning.domain.policy.ProvisioningCapabilityPolicy
import com.duluin.ftth.provisioning.domain.policy.StepCapabilityRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ProvisioningSafetyPolicyTest {
    private val tenantId = UuidV7.generate()
    private val device = DeviceReference(DeviceKind.OLT, UuidV7.generate())
    private val now = Instant.parse("2026-09-02T10:00:00Z")
    private val fingerprint = DeviceFingerprint(
        device = device,
        vendor = "HSGQ",
        model = "HSGQ-E04I",
        firmware = "V1.2.3",
        transport = "TELNET",
        operationClass = "VLAN_MEMBERSHIP",
    )

    @Test
    fun `production allows only an exact current certified path and returns all evidence ids`() {
        val capabilityId = UuidV7.generate()
        val certificationId = UuidV7.generate()
        val request = request(fingerprint, capabilityId, certificationId, CertificationStatus.CERTIFIED)

        val decision = ProvisioningCapabilityPolicy(now).evaluate(listOf(request), ExecutionMode.PRODUCTION_AUTO_APPLY)

        assertThat(decision.allowed).isTrue()
        assertThat(decision.code).isEqualTo(PolicyCode.AUTO_APPLY_ALLOWED)
        assertThat(decision.evidenceIds).containsExactlyInAnyOrder(capabilityId, certificationId)
        assertThat(decision.warnings).isEmpty()
    }

    @Test
    fun `production fails closed for stale mismatched provisional unsupported and manual evidence`() {
        val mismatched = fingerprint.copy(vendor = "ZTE")
        val cases = listOf(
            request(fingerprint, capabilityFingerprint = mismatched) to PolicyCode.FINGERPRINT_MISMATCH,
            request(fingerprint, capabilityExpiresAt = now.minusSeconds(1)) to PolicyCode.STALE_CAPABILITY_EVIDENCE,
            request(fingerprint, certificationStatus = CertificationStatus.PROVISIONAL) to PolicyCode.UNCERTIFIED_CAPABILITY,
            request(fingerprint, certificationStatus = CertificationStatus.UNSUPPORTED) to PolicyCode.UNSUPPORTED_CAPABILITY,
            request(fingerprint, certificationStatus = CertificationStatus.REQUIRES_MANUAL) to PolicyCode.REQUIRES_MANUAL,
        )

        cases.forEach { (request, expectedCode) ->
            val decision = ProvisioningCapabilityPolicy(now).evaluate(listOf(request), ExecutionMode.PRODUCTION_AUTO_APPLY)
            assertThat(decision.allowed).isFalse()
            assertThat(decision.code).isEqualTo(expectedCode)
            assertThat(decision.evidenceIds).isEmpty()
        }
    }

    @Test
    fun `production fails closed for missing and future dated evidence`() {
        val missingCapability = request(fingerprint).copy(capability = null)
        val missingCertification = request(fingerprint).copy(certification = null)
        val futureCapability = request(fingerprint, capabilityObservedAt = now.plusSeconds(1))

        val missingCapabilityDecision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(missingCapability),
            ExecutionMode.PRODUCTION_AUTO_APPLY,
        )
        val missingCertificationDecision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(missingCertification),
            ExecutionMode.PRODUCTION_AUTO_APPLY,
        )
        val futureCapabilityDecision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(futureCapability),
            ExecutionMode.PRODUCTION_AUTO_APPLY,
        )

        assertThat(missingCapabilityDecision.allowed).isFalse()
        assertThat(missingCapabilityDecision.code).isEqualTo(PolicyCode.MISSING_CAPABILITY_EVIDENCE)
        assertThat(missingCapabilityDecision.evidenceIds).isEmpty()
        assertThat(missingCertificationDecision.allowed).isFalse()
        assertThat(missingCertificationDecision.code).isEqualTo(PolicyCode.UNCERTIFIED_CAPABILITY)
        assertThat(missingCertificationDecision.evidenceIds).isEmpty()
        assertThat(futureCapabilityDecision.allowed).isFalse()
        assertThat(futureCapabilityDecision.code).isEqualTo(PolicyCode.STALE_CAPABILITY_EVIDENCE)
        assertThat(futureCapabilityDecision.evidenceIds).isEmpty()
    }

    @Test
    fun `certification is exact for device scope model firmware transport and operation class`() {
        val dimensions = listOf<(DeviceFingerprint) -> DeviceFingerprint>(
            { it.copy(device = DeviceReference(DeviceKind.OLT, UuidV7.generate())) },
            { it.copy(model = "HSGQ-E08I") },
            { it.copy(firmware = "V1.2.4") },
            { it.copy(transport = "SSH") },
            { it.copy(operationClass = "PPPOE_TERMINATION") },
        )

        dimensions.forEach { mismatch ->
            val decision = ProvisioningCapabilityPolicy(now).evaluate(
                listOf(request(fingerprint, certificationFingerprint = mismatch(fingerprint))),
                ExecutionMode.PRODUCTION_AUTO_APPLY,
            )
            assertThat(decision.allowed).isFalse()
            assertThat(decision.code).isEqualTo(PolicyCode.FINGERPRINT_MISMATCH)
        }
    }

    @Test
    fun `dry run includes provisional steps only with an explicit warning`() {
        val decision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(request(fingerprint, certificationStatus = CertificationStatus.PROVISIONAL)),
            ExecutionMode.DRY_RUN,
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.code).isEqualTo(PolicyCode.DRY_RUN_ALLOWED_WITH_WARNINGS)
        assertThat(decision.warnings).containsExactly("PROVISIONAL_CERTIFICATION:${device.kind}:${device.id}")
        assertThat(decision.evidenceIds).hasSize(2)
    }

    @Test
    fun `every production step must be supported and certified`() {
        val second = fingerprint.copy(device = DeviceReference(DeviceKind.BRAS, UuidV7.generate()), vendor = "MIKROTIK")
        val decision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(request(fingerprint), request(second, certificationStatus = CertificationStatus.PROVISIONAL)),
            ExecutionMode.PRODUCTION_AUTO_APPLY,
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.code).isEqualTo(PolicyCode.UNCERTIFIED_CAPABILITY)
    }

    @Test
    fun `platform admin cannot bypass any protected management resource`() {
        val policy = ProtectedManagementPolicy(
            ProtectedManagementResources(
                vlanRanges = listOf(VlanRange(99, 99), VlanRange(400, 410)),
                managementIpPrefixes = setOf("10.20.0.0/16", "2001:db8:10::/48"),
                vrfs = setOf("MGMT"),
                managementInterfaceRoles = setOf("MANAGEMENT", "OOB"),
                collectorSourcePaths = setOf("collector/site-a/uplink0"),
                requiredOutOfBandRoutes = setOf("oob/site-a"),
            ),
        )
        val admin = PolicyActor(platformAdmin = true, permissions = setOf("provisioning.segment.manage"))
        val oobAvailable = setOf("oob/site-a")
        val protectedMutations = listOf(
            ManagementMutation(vlanIds = setOf(99), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(vlanIds = setOf(405), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(interfaceRoles = setOf("MANAGEMENT"), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(interfaceRoles = setOf("OOB"), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(ipAddresses = setOf("10.20.3.4"), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(ipAddresses = setOf("2001:db8:10::8"), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(vrfOrRoutingInstances = setOf("MGMT"), availableOutOfBandRoutes = oobAvailable),
            ManagementMutation(
                collectorSourcePaths = setOf("collector/site-a/uplink0"),
                availableOutOfBandRoutes = oobAvailable,
            ),
            ManagementMutation(availableOutOfBandRoutes = emptySet()),
            ManagementMutation(
                changedOutOfBandRoutes = setOf("oob/site-a"),
                availableOutOfBandRoutes = setOf("oob/site-a"),
            ),
        )

        protectedMutations.forEach { mutation ->
            val decision = policy.evaluate(mutation, admin)
            assertThat(decision.allowed).isFalse()
            assertThat(decision.code).isEqualTo(PolicyCode.PROTECTED_MANAGEMENT_RESOURCE)
            assertThat(decision.evidenceIds).isEmpty()
        }
    }

    @Test
    fun `malformed mutation IP addresses fail closed for platform admin`() {
        val policy = ProtectedManagementPolicy(
            ProtectedManagementResources(
                managementIpPrefixes = setOf("10.20.0.0/16", "2001:db8:10::/48"),
            ),
        )
        val admin = PolicyActor(platformAdmin = true, permissions = setOf("provisioning.segment.manage"))
        val invalidAddresses = listOf(
            "",
            "   ",
            "999.20.1.1",
            "10.20.1",
            "10.20.1.1.5",
            "10.20.-1.1",
            "2001:::1",
            "2001:db8::not-hex",
            "management.example.com",
            "localhost",
        )

        invalidAddresses.forEach { address ->
            val decision = policy.evaluate(ManagementMutation(ipAddresses = setOf(address)), admin)

            assertThat(decision.allowed).describedAs("address=%s", address).isFalse()
            assertThat(decision.code).describedAs("address=%s", address)
                .isEqualTo(PolicyCode.INVALID_MANAGEMENT_RESOURCE)
            assertThat(decision.evidenceIds).isEmpty()
        }
    }

    @Test
    fun `customer resources with the required out of band route are allowed`() {
        val policy = ProtectedManagementPolicy(
            ProtectedManagementResources(
                vlanRanges = listOf(VlanRange(99, 99)),
                managementIpPrefixes = setOf("10.20.0.0/16"),
                vrfs = setOf("MGMT"),
                collectorSourcePaths = setOf("collector/site-a/uplink0"),
                requiredOutOfBandRoutes = setOf("oob/site-a"),
            ),
        )
        val mutation = ManagementMutation(
            vlanIds = setOf(200),
            interfaceRoles = setOf("TRUNK"),
            ipAddresses = setOf("172.16.1.2", "2001:db8:20::2"),
            vrfOrRoutingInstances = setOf("CUSTOMER"),
            collectorSourcePaths = setOf("collector/site-a/customer-uplink"),
            requiredOutOfBandRoutes = setOf("oob/site-a"),
            availableOutOfBandRoutes = setOf("oob/site-a"),
        )

        val decision = policy.evaluate(mutation, PolicyActor(platformAdmin = false, permissions = emptySet()))

        assertThat(decision.allowed).isTrue()
        assertThat(decision.code).isEqualTo(PolicyCode.MANAGEMENT_RESOURCES_CLEAR)
    }

    private fun request(
        requested: DeviceFingerprint,
        capabilityId: java.util.UUID = UuidV7.generate(),
        certificationId: java.util.UUID = UuidV7.generate(),
        certificationStatus: CertificationStatus = CertificationStatus.CERTIFIED,
        capabilityFingerprint: DeviceFingerprint = requested,
        certificationFingerprint: DeviceFingerprint = requested,
        capabilityObservedAt: Instant = now.minusSeconds(30),
        capabilityExpiresAt: Instant = now.plusSeconds(300),
    ) = StepCapabilityRequest(
        tenantId = tenantId,
        fingerprint = requested,
        capability = CapabilityEvidence(
            id = capabilityId,
            tenantId = tenantId,
            fingerprint = capabilityFingerprint,
            supported = true,
            observedAt = capabilityObservedAt,
            expiresAt = capabilityExpiresAt,
        ),
        certification = CertificationEvidence(
            id = certificationId,
            tenantId = tenantId,
            fingerprint = certificationFingerprint,
            status = certificationStatus,
            validUntil = now.plusSeconds(300),
        ),
    )
}
