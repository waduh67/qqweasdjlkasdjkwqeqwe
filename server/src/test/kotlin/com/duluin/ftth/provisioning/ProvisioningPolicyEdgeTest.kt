package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationEvidence
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.ProvisioningCapabilityPolicy
import com.duluin.ftth.provisioning.domain.policy.StepCapabilityRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ProvisioningPolicyEdgeTest {
    private val tenantId = UuidV7.generate()
    private val now = Instant.parse("2026-09-02T12:00:00Z")
    private val fingerprint = DeviceFingerprint(
        DeviceReference(DeviceKind.BRAS, UuidV7.generate()),
        "MIKROTIK",
        "CCR2004",
        "7.20.2",
        "HTTPS_REST",
        "ENSURE_PPPOE_TERMINATION",
    )

    @Test
    fun `production rejects empty path tenant mismatch and unsupported capability`() {
        val policy = ProvisioningCapabilityPolicy(now)

        val cases = listOf(
            emptyList<StepCapabilityRequest>() to PolicyCode.EMPTY_CAPABILITY_PATH,
            listOf(request().copy(tenantId = UuidV7.generate())) to PolicyCode.TENANT_SCOPE_MISMATCH,
            listOf(request(supported = false)) to PolicyCode.UNSUPPORTED_CAPABILITY,
        )

        cases.forEach { (steps, expected) ->
            val decision = policy.evaluate(steps, ExecutionMode.PRODUCTION_AUTO_APPLY)
            assertThat(decision.allowed).isFalse()
            assertThat(decision.code).isEqualTo(expected)
            assertThat(decision.evidenceIds).isEmpty()
        }
    }

    @Test
    fun `production rejects revoked expired and cross tenant certification`() {
        val cases = listOf(
            request(revoked = true) to PolicyCode.UNCERTIFIED_CAPABILITY,
            request(certificationValidUntil = now) to PolicyCode.STALE_CERTIFICATION_EVIDENCE,
            request(certificationTenantId = UuidV7.generate()) to PolicyCode.TENANT_SCOPE_MISMATCH,
        )

        cases.forEach { (step, expected) ->
            val decision = ProvisioningCapabilityPolicy(now).evaluate(
                listOf(step),
                ExecutionMode.PRODUCTION_AUTO_APPLY,
            )
            assertThat(decision.allowed).isFalse()
            assertThat(decision.code).isEqualTo(expected)
        }
    }

    @Test
    fun `certification vendor is part of exact fingerprint`() {
        val decision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(request(certificationFingerprint = fingerprint.copy(vendor = "JUNIPER"))),
            ExecutionMode.PRODUCTION_AUTO_APPLY,
        )

        assertThat(decision.allowed).isFalse()
        assertThat(decision.code).isEqualTo(PolicyCode.FINGERPRINT_MISMATCH)
    }

    @Test
    fun `simulator permits provisional evidence only with warning`() {
        val decision = ProvisioningCapabilityPolicy(now).evaluate(
            listOf(request(status = CertificationStatus.PROVISIONAL)),
            ExecutionMode.SIMULATOR,
        )

        assertThat(decision.allowed).isTrue()
        assertThat(decision.code).isEqualTo(PolicyCode.SIMULATION_ALLOWED_WITH_WARNINGS)
        assertThat(decision.warnings).containsExactly("PROVISIONAL_CERTIFICATION:${fingerprint.device.kind}:${fingerprint.device.id}")
    }

    private fun request(
        supported: Boolean = true,
        status: CertificationStatus = CertificationStatus.CERTIFIED,
        revoked: Boolean = false,
        certificationTenantId: UUID = tenantId,
        certificationValidUntil: Instant = now.plusSeconds(300),
        certificationFingerprint: DeviceFingerprint = fingerprint,
    ) = StepCapabilityRequest(
        tenantId,
        fingerprint,
        CapabilityEvidence(
            UuidV7.generate(),
            tenantId,
            fingerprint,
            supported,
            now.minusSeconds(30),
            now.plusSeconds(300),
        ),
        CertificationEvidence(
            UuidV7.generate(),
            certificationTenantId,
            certificationFingerprint,
            status,
            certificationValidUntil,
            revoked,
        ),
    )
}
