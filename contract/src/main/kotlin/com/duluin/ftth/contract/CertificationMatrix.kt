package com.duluin.ftth.contract

enum class CertificationEvidenceOrigin { SIMULATOR_FIXTURE, ADAPTER_FIXTURE, HARDWARE }
enum class CertificationVerdict { CERTIFIED_BY_TEST, PROVISIONAL }
enum class CertificationPhase { CREATE, VERIFY, IDEMPOTENT_REPEAT, ROLLBACK, DELETE, OBSERVATION_ONLY }

data class CertificationPhaseResult(val phase: CertificationPhase, val passed: Boolean, val detail: String) {
    init {
        require(detail.isNotBlank()) { "CERTIFICATION_PHASE_DETAIL_REQUIRED" }
    }
}

data class AdapterCertificationMatrixEntry(
    val profileId: String,
    val implementation: String,
    val fingerprint: DeviceFingerprint,
    val origin: CertificationEvidenceOrigin,
    val capabilities: Set<String>,
    val operationClasses: Set<String>,
    val unsupportedOperations: Map<String, String>,
    val verdict: CertificationVerdict,
    val phases: List<CertificationPhaseResult>,
    val evidenceIdentity: String?,
) {
    init {
        require(profileId.isNotBlank()) { "CERTIFICATION_PROFILE_REQUIRED" }
        require(implementation.isNotBlank()) { "CERTIFICATION_IMPLEMENTATION_REQUIRED" }
        require(unsupportedOperations.keys.intersect(operationClasses).isEmpty()) { "CERTIFICATION_CAPABILITY_CONTRADICTION" }
        require(phases.map(CertificationPhaseResult::phase).distinct().size == phases.size) {
            "CERTIFICATION_PHASE_DUPLICATE"
        }
        if (verdict == CertificationVerdict.CERTIFIED_BY_TEST) {
            require(origin == CertificationEvidenceOrigin.SIMULATOR_FIXTURE) { "HARDWARE_TEST_CERTIFICATION_FORBIDDEN" }
            require(capabilities.isNotEmpty() && operationClasses.isNotEmpty()) { "CERTIFICATION_CAPABILITY_SET_EMPTY" }
            require(phases.map(CertificationPhaseResult::phase).toSet() == CertificationPhase.entries.toSet()) {
                "CERTIFICATION_PHASE_INCOMPLETE"
            }
            require(phases.all(CertificationPhaseResult::passed)) { "CERTIFICATION_PHASE_FAILED" }
            require(evidenceIdentity?.matches(Regex("^[a-f0-9]{64}$")) == true) { "CERTIFICATION_EVIDENCE_INVALID" }
        } else {
            require(evidenceIdentity == null) { "PROVISIONAL_EVIDENCE_FORBIDDEN" }
        }
    }
}

interface AdapterCertificationSubject : AutoCloseable {
    val profileId: String
    val implementation: String
    val origin: CertificationEvidenceOrigin

    fun capabilityReport(): DeviceCapabilityReport
    fun executePhase(phase: CertificationPhase): CertificationPhaseResult
    fun verifyUnsupportedOperations(): Map<String, String>
    override fun close() = Unit
}
