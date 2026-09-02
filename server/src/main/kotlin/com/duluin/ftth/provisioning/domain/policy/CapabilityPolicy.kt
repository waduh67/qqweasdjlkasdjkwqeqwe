package com.duluin.ftth.provisioning.domain.policy

import com.duluin.ftth.provisioning.domain.model.DeviceReference
import java.time.Instant
import java.util.UUID

enum class CertificationStatus { CERTIFIED, PROVISIONAL, UNSUPPORTED, REQUIRES_MANUAL }

enum class ExecutionMode { PRODUCTION_AUTO_APPLY, DRY_RUN, SIMULATOR }

enum class PolicyCode {
    AUTO_APPLY_ALLOWED,
    DRY_RUN_ALLOWED,
    DRY_RUN_ALLOWED_WITH_WARNINGS,
    SIMULATION_ALLOWED,
    SIMULATION_ALLOWED_WITH_WARNINGS,
    EMPTY_CAPABILITY_PATH,
    TENANT_SCOPE_MISMATCH,
    FINGERPRINT_MISMATCH,
    MISSING_CAPABILITY_EVIDENCE,
    STALE_CAPABILITY_EVIDENCE,
    STALE_CERTIFICATION_EVIDENCE,
    UNSUPPORTED_CAPABILITY,
    UNCERTIFIED_CAPABILITY,
    REQUIRES_MANUAL,
    MISSING_MANAGEMENT_EVIDENCE,
    STALE_MANAGEMENT_EVIDENCE,
    INVALID_MANAGEMENT_RESOURCE,
    PROTECTED_MANAGEMENT_RESOURCE,
    MANAGEMENT_RESOURCES_CLEAR,
}

data class PolicyDecision(
    val allowed: Boolean,
    val code: PolicyCode,
    val warnings: List<String> = emptyList(),
    val evidenceIds: Set<UUID> = emptySet(),
)

data class DeviceFingerprint(
    val device: DeviceReference,
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val operationClass: String,
) {
    init {
        require(listOf(vendor, model, firmware, transport, operationClass).none(String::isBlank)) {
            "DEVICE_FINGERPRINT_INCOMPLETE"
        }
    }
}

data class CapabilityEvidence(
    val id: UUID,
    val tenantId: UUID,
    val fingerprint: DeviceFingerprint,
    val supported: Boolean,
    val observedAt: Instant,
    val expiresAt: Instant,
)

data class CertificationEvidence(
    val id: UUID,
    val tenantId: UUID,
    val fingerprint: DeviceFingerprint,
    val status: CertificationStatus,
    val validUntil: Instant,
    val revoked: Boolean = false,
)

data class StepCapabilityRequest(
    val tenantId: UUID,
    val fingerprint: DeviceFingerprint,
    val capability: CapabilityEvidence?,
    val certification: CertificationEvidence?,
)

class ProvisioningCapabilityPolicy(private val evaluatedAt: Instant) {
    fun evaluate(steps: List<StepCapabilityRequest>, mode: ExecutionMode): PolicyDecision {
        if (steps.isEmpty()) return denied(PolicyCode.EMPTY_CAPABILITY_PATH)

        val warnings = mutableListOf<String>()
        val evidenceIds = linkedSetOf<UUID>()
        for (step in steps) {
            val failure = validate(step)
            if (failure != null) return denied(failure)

            val capability = requireNotNull(step.capability)
            val certification = step.certification
            evidenceIds += capability.id

            when (certification?.status ?: CertificationStatus.PROVISIONAL) {
                CertificationStatus.CERTIFIED -> evidenceIds += requireNotNull(certification).id
                CertificationStatus.PROVISIONAL -> {
                    if (mode == ExecutionMode.PRODUCTION_AUTO_APPLY) return denied(PolicyCode.UNCERTIFIED_CAPABILITY)
                    certification?.let { evidenceIds += it.id }
                    warnings += "PROVISIONAL_CERTIFICATION:${step.fingerprint.device.kind}:${step.fingerprint.device.id}"
                }
                CertificationStatus.UNSUPPORTED -> return denied(PolicyCode.UNSUPPORTED_CAPABILITY)
                CertificationStatus.REQUIRES_MANUAL -> return denied(PolicyCode.REQUIRES_MANUAL)
            }
        }

        val code = when (mode) {
            ExecutionMode.PRODUCTION_AUTO_APPLY -> PolicyCode.AUTO_APPLY_ALLOWED
            ExecutionMode.DRY_RUN -> if (warnings.isEmpty()) PolicyCode.DRY_RUN_ALLOWED else PolicyCode.DRY_RUN_ALLOWED_WITH_WARNINGS
            ExecutionMode.SIMULATOR -> if (warnings.isEmpty()) PolicyCode.SIMULATION_ALLOWED else PolicyCode.SIMULATION_ALLOWED_WITH_WARNINGS
        }
        return PolicyDecision(true, code, warnings, evidenceIds)
    }

    private fun validate(step: StepCapabilityRequest): PolicyCode? {
        val capability = step.capability ?: return PolicyCode.MISSING_CAPABILITY_EVIDENCE
        if (capability.tenantId != step.tenantId) return PolicyCode.TENANT_SCOPE_MISMATCH
        if (capability.fingerprint != step.fingerprint) return PolicyCode.FINGERPRINT_MISMATCH
        if (capability.observedAt.isAfter(evaluatedAt) ||
            !capability.observedAt.isBefore(capability.expiresAt) ||
            !evaluatedAt.isBefore(capability.expiresAt)
        ) {
            return PolicyCode.STALE_CAPABILITY_EVIDENCE
        }
        if (!capability.supported) return PolicyCode.UNSUPPORTED_CAPABILITY

        val certification = step.certification ?: return null
        if (certification.tenantId != step.tenantId) return PolicyCode.TENANT_SCOPE_MISMATCH
        if (certification.fingerprint != step.fingerprint) return PolicyCode.FINGERPRINT_MISMATCH
        if (certification.revoked) return PolicyCode.UNCERTIFIED_CAPABILITY
        if (!evaluatedAt.isBefore(certification.validUntil)) return PolicyCode.STALE_CERTIFICATION_EVIDENCE
        return null
    }

    private fun denied(code: PolicyCode) = PolicyDecision(allowed = false, code = code)
}
