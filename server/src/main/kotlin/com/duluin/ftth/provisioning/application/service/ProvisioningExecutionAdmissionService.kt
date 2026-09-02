package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
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
) : ProvisioningExecutionAdmissionUseCase {
    @Transactional
    override fun admit(planId: UUID, keySuffix: String): ProvisionExecution {
        val plan = plans.findById(planId) ?: throw ValidationException("PLAN_NOT_FOUND")
        if (plan.status != PlanStatus.VALIDATED) throw ValidationException("PLAN_NOT_VALIDATED")
        safetyGate.requireAllowed(plan, ExecutionMode.PRODUCTION_AUTO_APPLY)
        val key = "${plan.intentId}:${plan.revision}:$keySuffix"
        executions.findByIdempotencyKey(key)?.let { return it }
        return executions.save(ProvisionExecution.queue(plan.tenantId, plan.intentId, plan.id, key))
    }
}
