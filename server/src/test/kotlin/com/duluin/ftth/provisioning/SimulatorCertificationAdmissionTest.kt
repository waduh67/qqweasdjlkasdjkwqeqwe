package com.duluin.ftth.provisioning

import com.duluin.ftth.contract.AdapterCertificationMatrixEntry
import com.duluin.ftth.contract.CertificationEvidenceOrigin
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.CertificationPhaseResult
import com.duluin.ftth.contract.CertificationVerdict
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.ProvisioningCapabilityPolicy
import com.duluin.ftth.provisioning.domain.policy.StepCapabilityRequest
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimulatorCertificationAdmissionTest {
    @Test
    fun `simulator matrix evidence never certifies the equivalent hardware path`() {
        val matrix = AdapterCertificationMatrixEntry(
            profileId = "routeros-simulator",
            implementation = "RouterOsProvisioningAdapter",
            fingerprint = com.duluin.ftth.contract.DeviceFingerprint("MIKROTIK", "CCR2004", "7.20.2", "HTTPS_REST"),
            origin = CertificationEvidenceOrigin.SIMULATOR_FIXTURE,
            capabilities = setOf("REST_RESOURCE_IDS"),
            operationClasses = setOf("ENSURE_PPPOE_TERMINATION"),
            unsupportedOperations = emptyMap(),
            verdict = CertificationVerdict.CERTIFIED_BY_TEST,
            phases = CertificationPhase.entries.map { CertificationPhaseResult(it, true, "PASS") },
            evidenceIdentity = "a".repeat(64),
        )
        val fingerprint = DeviceFingerprint(
            DeviceReference(DeviceKind.BRAS, DEVICE_ID),
            matrix.fingerprint.vendor,
            matrix.fingerprint.model,
            matrix.fingerprint.firmware,
            matrix.fingerprint.transport,
            "ENSURE_PPPOE_TERMINATION",
        )
        val request = StepCapabilityRequest(
            TENANT_ID,
            fingerprint,
            CapabilityEvidence(EVIDENCE_ID, TENANT_ID, fingerprint, true, NOW.minusSeconds(1), NOW.plusSeconds(60)),
            certification = null,
        )
        val policy = ProvisioningCapabilityPolicy(NOW)

        val simulation = policy.evaluate(listOf(request), ExecutionMode.SIMULATOR)
        val production = policy.evaluate(listOf(request), ExecutionMode.PRODUCTION_AUTO_APPLY)

        assertThat(simulation.allowed).isTrue()
        assertThat(production.allowed).isFalse()
        assertThat(production.code).isEqualTo(PolicyCode.UNCERTIFIED_CAPABILITY)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-03T12:00:00Z")
        val TENANT_ID: UUID = UUID.fromString("0199386e-9718-7000-8000-000000000101")
        val DEVICE_ID: UUID = UUID.fromString("0199386e-9718-7000-8000-000000000102")
        val EVIDENCE_ID: UUID = UUID.fromString("0199386e-9718-7000-8000-000000000103")
    }
}
