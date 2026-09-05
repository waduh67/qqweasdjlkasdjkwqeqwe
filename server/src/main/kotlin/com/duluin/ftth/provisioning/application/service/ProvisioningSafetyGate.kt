package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
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
import com.duluin.ftth.provisioning.domain.policy.ProvisioningCapabilityPolicy
import com.duluin.ftth.provisioning.domain.policy.StepCapabilityRequest
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Component

object SafetyPlanAttributes {
    const val VENDOR = "safety.vendor"
    const val MODEL = "safety.model"
    const val FIRMWARE = "safety.firmware"
    const val TRANSPORT = "safety.transport"
    const val MANAGEMENT_COMPLETE = "safety.managementComplete"
    const val MANAGEMENT_SOURCE_ID = "safety.managementSourceId"
    const val MANAGEMENT_SOURCE_TYPE = "safety.managementSourceType"
    const val INTERFACE_ROLES = "safety.interfaceRoles"
    const val IP_ADDRESSES = "safety.ipAddresses"
    const val VRFS = "safety.vrfs"
    const val COLLECTOR_PATHS = "safety.collectorPaths"
    const val REQUIRED_OOB_ROUTES = "safety.requiredOobRoutes"
    const val CHANGED_OOB_ROUTES = "safety.changedOobRoutes"
    const val AVAILABLE_OOB_ROUTES = "safety.availableOobRoutes"
}

interface ProvisioningSafetyGate {
    fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision

    fun evaluate(plan: ProvisionPlan, mode: ExecutionMode, scope: SafetyGateScope): PolicyDecision = evaluate(plan, mode)

    fun evaluateStep(
        plan: ProvisionPlan,
        stepId: UUID,
        mode: ExecutionMode,
        scope: SafetyGateScope,
    ): PolicyDecision = evaluate(plan, mode, scope)

    fun requireAllowed(
        plan: ProvisionPlan,
        mode: ExecutionMode,
        scope: SafetyGateScope = SafetyGateScope.FORWARD,
    ): PolicyDecision = evaluate(plan, mode, scope).also { decision ->
        if (!decision.allowed) throw ValidationException(decision.code.name)
    }

    fun requireStepAllowed(
        plan: ProvisionPlan,
        stepId: UUID,
        mode: ExecutionMode,
        scope: SafetyGateScope,
    ): PolicyDecision = evaluateStep(plan, stepId, mode, scope).also { decision ->
        if (!decision.allowed) throw ValidationException(decision.code.name)
    }
}

enum class SafetyGateScope { FORWARD, ROLLBACK }

@Component
class EvidenceBackedProvisioningSafetyGate(
    private val evidence: ProvisioningSafetyEvidenceRepository,
    private val clock: Clock,
) : ProvisioningSafetyGate {
    override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision =
        evaluate(plan, mode, SafetyGateScope.FORWARD)

    override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode, scope: SafetyGateScope): PolicyDecision =
        evaluateSteps(plan, plan.steps, mode, scope)

    override fun evaluateStep(
        plan: ProvisionPlan,
        stepId: UUID,
        mode: ExecutionMode,
        scope: SafetyGateScope,
    ): PolicyDecision {
        val step = plan.steps.singleOrNull { it.id == stepId } ?: return denied(PolicyCode.FINGERPRINT_MISMATCH)
        return evaluateSteps(plan, listOf(step), mode, scope)
    }

    private fun evaluateSteps(
        plan: ProvisionPlan,
        steps: List<ProvisionStep>,
        mode: ExecutionMode,
        scope: SafetyGateScope,
    ): PolicyDecision {
        val capabilityDecision = if (scope == SafetyGateScope.FORWARD) {
            val fingerprints = steps.map { step -> fingerprint(step) ?: return denied(PolicyCode.FINGERPRINT_MISMATCH) }
            val capabilityRequests = fingerprints.map { fingerprint ->
                StepCapabilityRequest(
                    plan.tenantId,
                    fingerprint,
                    evidence.findCapabilityEvidence(plan.tenantId, fingerprint),
                    evidence.findCertificationEvidence(plan.tenantId, fingerprint),
                )
            }
            ProvisioningCapabilityPolicy(clock.instant()).evaluate(capabilityRequests, mode)
        } else {
            PolicyDecision(true, PolicyCode.ROLLBACK_ALLOWED)
        }
        if (!capabilityDecision.allowed) return capabilityDecision

        val managementEvidenceIds = linkedSetOf<UUID>()
        for (step in steps) {
            val current = evidence.findManagementEvidence(plan.tenantId, step.device)
                ?: return denied(PolicyCode.MISSING_MANAGEMENT_EVIDENCE)
            val mutation = deriveManagementMutation(step, current)
                ?: return denied(PolicyCode.MISSING_MANAGEMENT_EVIDENCE)
            val evidenceFailure = validateManagementEvidence(plan, step, current)
            if (evidenceFailure != null) return denied(evidenceFailure)
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

    private fun deriveManagementMutation(
        step: ProvisionStep,
        current: ManagementSafetyEvidence,
    ): ManagementMutation? {
        if (!current.complete || current.sourceEvidenceId == null || current.sourceType == null) return null
        if (step.attributes[SafetyPlanAttributes.MANAGEMENT_COMPLETE] != "true") return null
        if (step.attributes[SafetyPlanAttributes.MANAGEMENT_SOURCE_ID] != current.sourceEvidenceId.toString()) return null
        if (step.attributes[SafetyPlanAttributes.MANAGEMENT_SOURCE_TYPE] != current.sourceType.name) return null
        val requiredKeys = listOf(
            SafetyPlanAttributes.INTERFACE_ROLES,
            SafetyPlanAttributes.IP_ADDRESSES,
            SafetyPlanAttributes.VRFS,
            SafetyPlanAttributes.COLLECTOR_PATHS,
            SafetyPlanAttributes.REQUIRED_OOB_ROUTES,
            SafetyPlanAttributes.CHANGED_OOB_ROUTES,
            SafetyPlanAttributes.AVAILABLE_OOB_ROUTES,
        )
        if (!step.attributes.keys.containsAll(requiredKeys)) return null
        val vlanId = step.attributes["vlanId"]?.toIntOrNull() ?: return null
        fun values(key: String) = step.attributes.getValue(key).split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        val plannedAvailableRoutes = values(SafetyPlanAttributes.AVAILABLE_OOB_ROUTES)
        if (plannedAvailableRoutes != current.availableOutOfBandRoutes) return null
        return ManagementMutation(
            vlanIds = setOf(vlanId),
            interfaceRoles = values(SafetyPlanAttributes.INTERFACE_ROLES),
            ipAddresses = values(SafetyPlanAttributes.IP_ADDRESSES),
            vrfOrRoutingInstances = values(SafetyPlanAttributes.VRFS),
            collectorSourcePaths = values(SafetyPlanAttributes.COLLECTOR_PATHS),
            requiredOutOfBandRoutes = values(SafetyPlanAttributes.REQUIRED_OOB_ROUTES),
            changedOutOfBandRoutes = values(SafetyPlanAttributes.CHANGED_OOB_ROUTES),
            availableOutOfBandRoutes = current.availableOutOfBandRoutes,
        )
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
