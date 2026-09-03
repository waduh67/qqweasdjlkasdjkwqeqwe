package com.duluin.ftth.simulator.network

import com.duluin.ftth.contract.AdapterCertificationMatrixEntry
import com.duluin.ftth.contract.CertificationEvidenceOrigin
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.CertificationPhaseResult
import com.duluin.ftth.contract.CertificationVerdict
import com.duluin.ftth.contract.DeviceFingerprint
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CertificationMatrixRunner(
    private val faults: DeterministicFaultScript = DeterministicFaultScript(),
) {
    fun certify(profile: SimulatorProfile): AdapterCertificationMatrixEntry {
        val simulator = DeterministicNetworkSimulator(profile, faults)
        val phases = mutableListOf<CertificationPhaseResult>()
        val created = simulator.create(CERTIFICATION_VLAN)
        phases += result(CertificationPhase.CREATE, created == SimulatorTerminalState.SUCCEEDED, created.name)
        phases += result(CertificationPhase.VERIFY, simulator.verify(CERTIFICATION_VLAN), simulator.state().sha256())
        val beforeRepeat = simulator.mutationCount
        val repeated = simulator.create(CERTIFICATION_VLAN)
        phases += result(
            CertificationPhase.IDEMPOTENT_REPEAT,
            repeated == SimulatorTerminalState.SUCCEEDED && simulator.mutationCount == beforeRepeat && !simulator.hasDuplicateResources(),
            repeated.name,
        )
        val rolledBack = simulator.rollback()
        phases += result(CertificationPhase.ROLLBACK, rolledBack == SimulatorTerminalState.ROLLED_BACK, rolledBack.name)
        val deleted = simulator.delete(CERTIFICATION_VLAN)
        phases += result(CertificationPhase.DELETE, deleted == SimulatorTerminalState.SUCCEEDED && simulator.state().isEmpty(), deleted.name)
        val beforeObservation = simulator.mutationCount
        simulator.observe()
        phases += result(CertificationPhase.OBSERVATION_ONLY, simulator.mutationCount == beforeObservation, "PREFLIGHT")

        val passed = phases.all(CertificationPhaseResult::passed)
        val certified = passed && profile.origin == FingerprintOrigin.SIMULATOR
        val identity = if (certified) evidenceIdentity(profile, phases) else null
        return AdapterCertificationMatrixEntry(
            profileId = profile.id,
            fingerprint = DeviceFingerprint(
                profile.fingerprint.vendor,
                profile.fingerprint.model,
                profile.fingerprint.firmware,
                profile.fingerprint.transport,
            ),
            origin = if (profile.origin == FingerprintOrigin.SIMULATOR) {
                CertificationEvidenceOrigin.SIMULATOR_FIXTURE
            } else {
                CertificationEvidenceOrigin.HARDWARE
            },
            capabilities = profile.capabilities.mapTo(sortedSetOf()) { it.name },
            operationClasses = profile.supportedOperations.toSortedSet(),
            verdict = if (certified) CertificationVerdict.CERTIFIED_BY_TEST else CertificationVerdict.PROVISIONAL,
            phases = phases.toList(),
            evidenceIdentity = identity,
        )
    }

    private fun result(phase: CertificationPhase, passed: Boolean, detail: String) =
        CertificationPhaseResult(phase, passed, detail)

    private fun evidenceIdentity(profile: SimulatorProfile, phases: List<CertificationPhaseResult>): String {
        val canonical = buildString {
            append(profile.id).append('|')
            append(profile.fingerprint.vendor).append('|')
            append(profile.fingerprint.model).append('|')
            append(profile.fingerprint.firmware).append('|')
            append(profile.fingerprint.transport).append('|')
            profile.capabilities.map(SimulatorCapability::name).sorted().forEach { append(it).append('|') }
            profile.supportedOperations.sorted().forEach { append(it).append('|') }
            phases.forEach { append(it.phase.name).append(':').append(it.passed).append(':').append(it.detail).append('|') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CERTIFICATION_VLAN = 317
    }
}

object CertificationMatrixWriter {
    fun write(path: Path, reports: List<AdapterCertificationMatrixEntry>) {
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.writeString(path, toJson(reports), StandardCharsets.UTF_8)
    }

    fun toJson(reports: List<AdapterCertificationMatrixEntry>): String = reports.sortedBy { it.profileId }
        .joinToString(prefix = "{\"schemaVersion\":1,\"profiles\":[", postfix = "]}") { report ->
            buildString {
                append("{\"profileId\":").append(jsonString(report.profileId))
                append(",\"fingerprint\":{\"vendor\":").append(jsonString(report.fingerprint.vendor))
                append(",\"model\":").append(jsonString(report.fingerprint.model))
                append(",\"firmware\":").append(jsonString(report.fingerprint.firmware))
                append(",\"transport\":").append(jsonString(report.fingerprint.transport)).append('}')
                append(",\"origin\":\"").append(report.origin.name).append("\"")
                append(",\"status\":\"").append(report.verdict.name).append("\"")
                append(",\"capabilities\":[")
                append(report.capabilities.sorted().joinToString(",", transform = ::jsonString))
                append("],\"operationClasses\":[")
                append(report.operationClasses.sorted().joinToString(",", transform = ::jsonString))
                append(']')
                report.evidenceIdentity?.let { append(",\"evidenceIdentity\":\"").append(it).append("\"") }
                append(",\"phases\":[")
                append(report.phases.joinToString(",") { phase ->
                    "{\"phase\":\"${phase.phase.name}\",\"passed\":${phase.passed},\"detail\":${jsonString(phase.detail)}}"
                })
                append("]}")
            }
        }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
