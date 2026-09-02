package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanEvaluation
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProvisioningLifecycleService(
    private val plans: ProvisionPlanRepository,
    private val executions: ProvisionExecutionRepository,
    private val admission: ProvisioningExecutionAdmissionUseCase,
    private val safetyGate: ProvisioningSafetyGate,
) {
    @Transactional(readOnly = true)
    fun plan(id: UUID): ProvisionPlan = plans.findById(id) ?: throw NotFoundException("PLAN_NOT_FOUND")

    fun preview(id: UUID, mode: ExecutionMode): ProvisioningPlanEvaluation {
        if (mode == ExecutionMode.PRODUCTION_AUTO_APPLY) throw ConflictException("PREVIEW_MODE_INVALID")
        val plan = plan(id)
        return ProvisioningPlanEvaluation(plan, safetyGate.requireAllowed(plan, mode))
    }

    @Transactional
    fun apply(id: UUID, revision: Int, idempotencyKey: String): ProvisionExecution {
        val plan = plan(id)
        if (plan.revision != revision) throw ConflictException("STALE_PLAN")
        if (idempotencyKey.isBlank()) throw ConflictException("IDEMPOTENCY_KEY_REQUIRED")
        return admission.admit(id, idempotencyKey)
    }

    @Transactional(readOnly = true)
    fun execution(id: UUID): ProvisionExecution = executions.findById(id) ?: throw NotFoundException("EXECUTION_NOT_FOUND")

    @Transactional
    fun cancel(id: UUID): ProvisionExecution {
        val execution = execution(id)
        execution.cancel()
        return executions.save(execution)
    }
}
