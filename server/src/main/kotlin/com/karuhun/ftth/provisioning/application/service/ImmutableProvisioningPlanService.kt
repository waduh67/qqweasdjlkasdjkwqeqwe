package com.karuhun.ftth.provisioning.application.service

import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.provisioning.application.port.inbound.ProvisioningPlanEvaluation
import com.karuhun.ftth.provisioning.application.port.inbound.ProvisioningPlanningUseCase
import com.karuhun.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.karuhun.ftth.provisioning.domain.model.PlanStatus
import com.karuhun.ftth.provisioning.domain.model.ProvisionPlan
import com.karuhun.ftth.provisioning.domain.policy.ExecutionMode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ImmutableProvisioningPlanService(
    private val planner: CanonicalProvisioningPlanner,
    private val plans: ProvisionPlanRepository,
    private val safetyGate: ProvisioningSafetyGate,
) : ProvisioningPlanningUseCase {
    @Transactional
    override fun generate(request: PlanCompilationRequest): ProvisionPlan {
        plans.lockIntent(request.intent.id)
        val current = plans.findLatestByIntentId(request.intent.id)
        val candidate = planner.compile(request, (current?.revision ?: 0) + 1)
        if (current != null && current.preconditionHash == candidate.preconditionHash) return current
        if (current?.status == PlanStatus.GENERATED) current.reject()
        if (current?.status == PlanStatus.VALIDATED) current.supersede()
        if (current != null) plans.save(current)
        return plans.save(candidate)
    }

    @Transactional
    fun plan(request: PlanCompilationRequest): ProvisionPlan = validateProduction(request)

    @Transactional
    override fun validateProduction(request: PlanCompilationRequest): ProvisionPlan {
        plans.lockIntent(request.intent.id)
        val current = plans.findLatestByIntentId(request.intent.id)
        val comparison = planner.compile(request, current?.revision ?: 1)
        if (current != null && current.preconditionHash == comparison.preconditionHash) {
            safetyGate.requireAllowed(current, ExecutionMode.PRODUCTION_AUTO_APPLY)
            if (current.status == PlanStatus.GENERATED) current.validate()
            return plans.save(current)
        }

        val candidate = planner.compile(request, (current?.revision ?: 0) + 1)
        safetyGate.requireAllowed(candidate, ExecutionMode.PRODUCTION_AUTO_APPLY)
        candidate.validate()

        if (current?.status == PlanStatus.GENERATED) current.reject()
        if (current?.status == PlanStatus.VALIDATED) current.supersede()
        if (current != null) plans.save(current)
        return plans.save(candidate)
    }

    @Transactional(readOnly = true)
    override fun preview(request: PlanCompilationRequest, mode: ExecutionMode): ProvisioningPlanEvaluation {
        if (mode == ExecutionMode.PRODUCTION_AUTO_APPLY) throw ValidationException("PREVIEW_MODE_INVALID")
        val revision = (plans.findLatestByIntentId(request.intent.id)?.revision ?: 0) + 1
        val candidate = planner.compile(request, revision)
        return ProvisioningPlanEvaluation(candidate, safetyGate.requireAllowed(candidate, mode))
    }
}
