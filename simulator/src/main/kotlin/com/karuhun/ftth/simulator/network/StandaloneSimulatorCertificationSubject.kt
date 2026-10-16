package com.karuhun.ftth.simulator.network

import com.karuhun.ftth.contract.AdapterCertificationSubject
import com.karuhun.ftth.contract.CertificationEvidenceOrigin
import com.karuhun.ftth.contract.CertificationPhase
import com.karuhun.ftth.contract.CertificationPhaseResult
import com.karuhun.ftth.contract.DeviceCapabilityReport
import com.karuhun.ftth.contract.DeviceFingerprint
import java.time.Instant

class StandaloneSimulatorCertificationSubject(
    private val profile: SimulatorProfile,
    faults: DeterministicFaultScript = DeterministicFaultScript(),
) : AdapterCertificationSubject {
    private val simulator = DeterministicNetworkSimulator(profile, faults)
    override val profileId: String = profile.id
    override val implementation: String = DeterministicNetworkSimulator::class.qualifiedName.orEmpty()
    override val origin = if (profile.origin == FingerprintOrigin.SIMULATOR) {
        CertificationEvidenceOrigin.SIMULATOR_FIXTURE
    } else {
        CertificationEvidenceOrigin.HARDWARE
    }

    override fun capabilityReport() = DeviceCapabilityReport(
        targetId = profile.id,
        fingerprint = DeviceFingerprint(
            profile.fingerprint.vendor,
            profile.fingerprint.model,
            profile.fingerprint.firmware,
            profile.fingerprint.transport,
        ),
        capabilities = profile.capabilities.mapTo(sortedSetOf()) { it.name },
        reportedAt = Instant.EPOCH,
        operationClasses = profile.supportedOperations,
    )

    override fun executePhase(phase: CertificationPhase): CertificationPhaseResult = when (phase) {
        CertificationPhase.CREATE -> result(phase, simulator.create(VLAN) == SimulatorTerminalState.SUCCEEDED)
        CertificationPhase.VERIFY -> result(phase, simulator.verify(VLAN))
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val mutations = simulator.mutationCount
            result(phase, simulator.create(VLAN) == SimulatorTerminalState.SUCCEEDED && simulator.mutationCount == mutations)
        }
        CertificationPhase.ROLLBACK -> result(phase, simulator.rollback() == SimulatorTerminalState.ROLLED_BACK)
        CertificationPhase.DELETE -> result(phase, simulator.delete(VLAN) == SimulatorTerminalState.SUCCEEDED)
        CertificationPhase.OBSERVATION_ONLY -> {
            val mutations = simulator.mutationCount
            simulator.observe()
            result(phase, simulator.mutationCount == mutations)
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> = mapOf(
        "UNKNOWN_OPERATION" to if (simulator.supports("UNKNOWN_OPERATION")) "UNEXPECTED_SUPPORT" else "UNSUPPORTED_CAPABILITY",
    )

    private fun result(phase: CertificationPhase, passed: Boolean) =
        CertificationPhaseResult(phase, passed, if (passed) "PASS" else "FAIL")

    private companion object {
        const val VLAN = 317
    }
}
