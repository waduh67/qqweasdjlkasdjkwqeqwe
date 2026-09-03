package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProvisioningExecutionAdmissionService(
    private val plans: ProvisionPlanRepository,
    private val executions: ProvisionExecutionRepository,
    private val safetyGate: ProvisioningSafetyGate,
    private val rollout: ProvisioningRolloutProperties,
    private val metrics: ProvisioningMetrics? = null,
) : ProvisioningExecutionAdmissionUseCase {
    @Transactional
    override fun admit(planId: UUID, keySuffix: String, affectedSubscriberCount: Int): ProvisionExecution {
        val plan = plans.findById(planId) ?: throw ValidationException("PLAN_NOT_FOUND")
        if (plan.status !in setOf(PlanStatus.GENERATED, PlanStatus.VALIDATED)) throw ValidationException("PLAN_NOT_VALIDATED")
        rollout.requireAutoApplyAllowed(affectedSubscriberCount)
        val decision = safetyGate.evaluate(plan, ExecutionMode.PRODUCTION_AUTO_APPLY)
        if (!decision.allowed) {
            if (decision.code.name.contains("CERTIFICATION") || decision.code.name.contains("CERTIFIED")) {
                metrics?.certificationBlock()
            }
            throw ValidationException(decision.code.name)
        }
        if (plan.status == PlanStatus.GENERATED) {
            plan.validate()
            plans.save(plan)
        }
        executions.findByIdempotencyKey(keySuffix)?.let { existing ->
            if (existing.planId != plan.id) throw com.duluin.ftth.common.domain.error.ConflictException(
                "EXECUTION_IDEMPOTENCY_KEY_REUSED",
            )
            return existing
        }
        return executions.save(ProvisionExecution.queue(plan.tenantId, plan.intentId, plan.id, keySuffix))
    }
}
