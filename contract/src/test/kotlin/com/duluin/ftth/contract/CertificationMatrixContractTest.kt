package com.duluin.ftth.contract

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CertificationMatrixContractTest {
    @Test
    fun `hardware and adapter fixtures cannot claim simulator certification`() {
        listOf(CertificationEvidenceOrigin.HARDWARE, CertificationEvidenceOrigin.ADAPTER_FIXTURE).forEach { origin ->
            assertFailsWith<IllegalArgumentException> {
                AdapterCertificationMatrixEntry(
                    profileId = "hardware",
                    implementation = "HardwareFixture",
                    fingerprint = DeviceFingerprint("HSGQ", "HSGQ-E04I", "V1.0.0", "SSH"),
                    origin = origin,
                    capabilities = setOf("READBACK"),
                    operationClasses = setOf("VERIFY_STATE"),
                    unsupportedOperations = emptyMap(),
                    verdict = CertificationVerdict.CERTIFIED_BY_TEST,
                    phases = CertificationPhase.entries.map { CertificationPhaseResult(it, true, "PASS") },
                    evidenceIdentity = "a".repeat(64),
                )
            }
        }
    }

    @Test
    fun `certified evidence requires every successful phase`() {
        assertFailsWith<IllegalArgumentException> {
            AdapterCertificationMatrixEntry(
                profileId = "simulator",
                implementation = "NetworkSimulatorFixture",
                fingerprint = DeviceFingerprint("FTTH", "NETWORK-SIMULATOR", "1.0.0", "IN_MEMORY"),
                origin = CertificationEvidenceOrigin.SIMULATOR_FIXTURE,
                capabilities = emptySet(),
                operationClasses = emptySet(),
                unsupportedOperations = emptyMap(),
                verdict = CertificationVerdict.CERTIFIED_BY_TEST,
                phases = listOf(CertificationPhaseResult(CertificationPhase.CREATE, true, "PASS")),
                evidenceIdentity = "a".repeat(64),
            )
        }
    }
}
