package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ManagementMutation
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.PolicyActor
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.PolicyDecision
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementPolicy
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import com.duluin.ftth.provisioning.domain.policy.ProvisioningCapabilityPolicy
import com.duluin.ftth.provisioning.domain.policy.StepCapabilityRequest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

object SafetyPlanAttributes {
    const val VENDOR = "safety.vendor"
    const val MODEL = "safety.model"
    const val FIRMWARE = "safety.firmware"
    const val TRANSPORT = "safety.transport"
}

data class ManagementSafetyEvidence(
    val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    val protectedResources: ProtectedManagementResources,
    val mutation: ManagementMutation,
    val observedAt: Instant,
    val validUntil: Instant,
)

interface ProvisioningSafetyGate {
    fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision

    fun requireAllowed(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision = evaluate(plan, mode).also { decision ->
        if (!decision.allowed) throw ValidationException(decision.code.name)
    }
}

@Component
class EvidenceBackedProvisioningSafetyGate(
    private val evidence: ProvisioningSafetyEvidenceRepository,
    private val clock: Clock,
) : ProvisioningSafetyGate {
    override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision {
        val fingerprints = plan.steps.map { step -> fingerprint(step) ?: return denied(PolicyCode.FINGERPRINT_MISMATCH) }
        val capabilityRequests = fingerprints.map { fingerprint ->
            StepCapabilityRequest(
                plan.tenantId,
                fingerprint,
                evidence.findCapabilityEvidence(plan.tenantId, fingerprint),
                evidence.findCertificationEvidence(plan.tenantId, fingerprint),
            )
        }
        val capabilityDecision = ProvisioningCapabilityPolicy(clock.instant()).evaluate(capabilityRequests, mode)
        if (!capabilityDecision.allowed) return capabilityDecision

        val managementEvidenceIds = linkedSetOf<UUID>()
        for (step in plan.steps) {
            val current = evidence.findManagementEvidence(plan.tenantId, step.device)
                ?: return denied(PolicyCode.MISSING_MANAGEMENT_EVIDENCE)
            val evidenceFailure = validateManagementEvidence(plan, step, current)
            if (evidenceFailure != null) return denied(evidenceFailure)
            val vlanId = step.attributes["vlanId"]?.toIntOrNull()
                ?: return denied(PolicyCode.INVALID_MANAGEMENT_RESOURCE)
            val mutation = current.mutation.copy(vlanIds = current.mutation.vlanIds + vlanId)
            val managementDecision = ProtectedManagementPolicy(current.protectedResources).evaluate(mutation, SYSTEM_ACTOR)
            if (!managementDecision.allowed) return managementDecision
            managementEvidenceIds += current.id
        }
        return capabilityDecision.copy(evidenceIds = capabilityDecision.evidenceIds + managementEvidenceIds)
    }

    private fun fingerprint(step: ProvisionStep): DeviceFingerprint? {
        val vendor = step.attributes[SafetyPlanAttributes.VENDOR]?.takeIf(String::isNotBlank) ?: return null
        val model = step.attributes[SafetyPlanAttributes.MODEL]?.takeIf(String::isNotBlank) ?: return null
        val firmware = step.attributes[SafetyPlanAttributes.FIRMWARE]?.takeIf(String::isNotBlank) ?: return null
        val transport = step.attributes[SafetyPlanAttributes.TRANSPORT]?.takeIf(String::isNotBlank) ?: return null
        return DeviceFingerprint(step.device, vendor, model, firmware, transport, step.operation.name)
    }

    private fun validateManagementEvidence(
        plan: ProvisionPlan,
        step: ProvisionStep,
        current: ManagementSafetyEvidence,
    ): PolicyCode? {
        if (current.tenantId != plan.tenantId) return PolicyCode.TENANT_SCOPE_MISMATCH
        if (current.device != step.device) return PolicyCode.FINGERPRINT_MISMATCH
        val evaluatedAt = clock.instant()
        if (current.observedAt.isAfter(evaluatedAt) ||
            !current.observedAt.isBefore(current.validUntil) ||
            !evaluatedAt.isBefore(current.validUntil)
        ) {
            return PolicyCode.STALE_MANAGEMENT_EVIDENCE
        }
        return null
    }

    private fun denied(code: PolicyCode) = PolicyDecision(false, code)

    private companion object {
        val SYSTEM_ACTOR = PolicyActor(platformAdmin = false, permissions = emptySet())
    }
}
