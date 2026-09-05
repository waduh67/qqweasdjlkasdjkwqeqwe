package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanEvaluation
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProvisioningLifecycleService(
    private val plans: ProvisionPlanRepository,
    private val executions: ProvisionExecutionRepository,
    private val admission: ProvisioningExecutionAdmissionUseCase,
    private val safetyGate: ProvisioningSafetyGate,
    private val rollout: ProvisioningRolloutProperties,
    private val intents: ServiceIntentRepository,
    private val audit: ProvisioningAuditPublisher? = null,
    private val metrics: ProvisioningMetrics? = null,
    private val revisions: ProvisioningResourceRevisionStore? = null,
) {
    @Transactional(readOnly = true)
    fun plan(id: UUID): ProvisionPlan = plans.findById(id) ?: throw NotFoundException("PLAN_NOT_FOUND")

    fun preview(id: UUID, mode: ExecutionMode): ProvisioningPlanEvaluation {
        rollout.requirePlannerEnabled()
        if (mode == ExecutionMode.PRODUCTION_AUTO_APPLY) throw ConflictException("PREVIEW_MODE_INVALID")
        val plan = plan(id)
        return ProvisioningPlanEvaluation(plan, safetyGate.requireAllowed(plan, mode))
    }

    @Transactional
    fun apply(id: UUID, revision: Int, idempotencyKey: String): ProvisionExecution {
        val plan = plan(id)
        if (plan.revision != revision) throw ConflictException("STALE_PLAN")
        if (idempotencyKey.isBlank()) throw ConflictException("IDEMPOTENCY_KEY_REQUIRED")
        val execution = admission.admit(id, idempotencyKey)
        revisions?.register(EXECUTION, execution.id)
        audit?.publish(ProvisioningAuditRecord(execution.tenantId, "provisioning.execution.applied", "ProvisionExecution", execution.id))
        metrics?.queueDepth(1)
        return execution
    }

    @Transactional(readOnly = true)
    fun execution(id: UUID): ProvisionExecution = executions.findById(id) ?: throw NotFoundException("EXECUTION_NOT_FOUND")

    @Transactional
    fun cancel(id: UUID, revision: Int): ProvisionExecution {
        val execution = execution(id)
        if (revisions == null && revision != 1) throw ConflictException("STALE_REVISION")
        revisions?.advance(EXECUTION, id, revision)
        execution.cancel()
        val saved = executions.save(execution)
        audit?.publish(ProvisioningAuditRecord(saved.tenantId, "provisioning.execution.cancelled", "ProvisionExecution", saved.id))
        metrics?.queueDepth(0)
        return saved
    }

    fun executionRevision(id: UUID): Int = revisions?.current(EXECUTION, id) ?: 1

    private companion object { const val EXECUTION = "EXECUTION" }
}
