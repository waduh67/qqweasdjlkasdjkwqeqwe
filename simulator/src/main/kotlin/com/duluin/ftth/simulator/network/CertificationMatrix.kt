package com.duluin.ftth.simulator.network

import com.duluin.ftth.contract.AdapterCertificationMatrixEntry
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationEvidenceOrigin
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.CertificationPhaseResult
import com.duluin.ftth.contract.CertificationVerdict
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CertificationMatrixRunner {
    fun certify(subject: AdapterCertificationSubject): AdapterCertificationMatrixEntry = subject.use {
        val report = it.capabilityReport()
        val phases = CertificationPhase.entries.map(it::executePhase)
        val unsupported = it.verifyUnsupportedOperations().toSortedMap()
        val passed = phases.all(CertificationPhaseResult::passed)
        val certified = passed && it.origin == CertificationEvidenceOrigin.SIMULATOR_FIXTURE
        val identity = if (certified) evidenceIdentity(it.implementation, report, phases, unsupported) else null
        return AdapterCertificationMatrixEntry(
            profileId = it.profileId,
            implementation = it.implementation,
            fingerprint = report.fingerprint,
            origin = it.origin,
            capabilities = report.capabilities.toSortedSet(),
            operationClasses = report.operationClasses.toSortedSet(),
            unsupportedOperations = unsupported,
            verdict = if (certified) CertificationVerdict.CERTIFIED_BY_TEST else CertificationVerdict.PROVISIONAL,
            phases = phases,
            evidenceIdentity = identity,
        )
    }

    private fun evidenceIdentity(
        implementation: String,
        report: com.duluin.ftth.contract.DeviceCapabilityReport,
        phases: List<CertificationPhaseResult>,
        unsupported: Map<String, String>,
    ): String {
        val canonical = buildString {
            append(implementation).append('|')
            append(report.fingerprint.vendor).append('|')
            append(report.fingerprint.model).append('|')
            append(report.fingerprint.firmware).append('|')
            append(report.fingerprint.transport).append('|')
            report.capabilities.sorted().forEach { append(it).append('|') }
            report.operationClasses.sorted().forEach { append(it).append('|') }
            unsupported.forEach { (operation, result) -> append(operation).append(':').append(result).append('|') }
            phases.forEach { append(it.phase.name).append(':').append(it.passed).append(':').append(it.detail).append('|') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
                append(",\"implementation\":").append(jsonString(report.implementation))
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
                append("],\"unsupportedOperations\":{")
                append(report.unsupportedOperations.entries.joinToString(",") { (operation, result) ->
                    "${jsonString(operation)}:${jsonString(result)}"
                })
                append('}')
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
