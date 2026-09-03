package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionRunner
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanningUseCase
import com.duluin.ftth.provisioning.application.port.outbound.SubscriberAccessIsolationPort
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class SubscriberSessionEvidence(
    val activeSessionCount: Int,
    val observedAt: Instant,
) {
    init {
        require(activeSessionCount >= 0) { "ACTIVE_SESSION_COUNT_INVALID" }
    }
}

data class ProvisioningWorkflowCommand(
    val compilation: PlanCompilationRequest,
    val idempotencyKey: String,
    val expectedPlanPreconditionHash: String? = null,
    val forceDisconnect: Boolean = false,
    val forceDisconnectAuthorized: Boolean = false,
    val affectedSubscriberIds: Set<UUID> = setOf(compilation.intent.subjectId),
    @Deprecated("Ignored; server rollout configuration is authoritative")
    val maxAffectedSubscribers: Int = 1,
)

enum class ProvisioningWorkflowDisposition {
    EXECUTED,
    REPLACEMENT_PLAN_REQUIRED,
    ACCESS_ISOLATED,
}

data class ProvisioningWorkflowResult(
    val disposition: ProvisioningWorkflowDisposition,
    val plan: ProvisionPlan?,
    val execution: ProvisionExecution?,
)

class ProvisioningWorkflowService(
    private val planning: ProvisioningPlanningUseCase,
    private val admission: ProvisioningExecutionAdmissionUseCase,
    private val accessIsolation: SubscriberAccessIsolationPort,
    private val executionRunner: ProvisioningExecutionRunner,
    private val clock: Clock,
    private val rollout: ProvisioningRolloutProperties,
    private val maximumEvidenceAge: Duration = Duration.ofMinutes(5),
) {
    fun create(command: ProvisioningWorkflowCommand, ownerId: String = DEFAULT_OWNER): ProvisioningWorkflowResult {
        requireChange(command, PlanChange.CREATE)
        requireCanary(command)
        requireFreshEvidence(command.compilation)
        return execute(planning.validateProduction(command.compilation), command, ownerId)
    }

    fun update(command: ProvisioningWorkflowCommand, ownerId: String = DEFAULT_OWNER): ProvisioningWorkflowResult {
        requireChange(command, PlanChange.CREATE)
        requireCanary(command)
        requireFreshEvidence(command.compilation)
        val expectedHash = command.expectedPlanPreconditionHash
            ?: throw ConflictException("UPDATE_EXPECTED_PLAN_HASH_REQUIRED")
        val replacement = planning.validateProduction(command.compilation)
        if (replacement.preconditionHash != expectedHash) {
            return ProvisioningWorkflowResult(
                ProvisioningWorkflowDisposition.REPLACEMENT_PLAN_REQUIRED,
                replacement,
                null,
            )
        }
        return execute(replacement, command, ownerId)
    }

    fun suspend(
        subscriptionId: UUID,
    ): ProvisioningWorkflowResult {
        rollout.requireAutoApplyAllowed(1)
        accessIsolation.isolate(subscriptionId)
        return ProvisioningWorkflowResult(ProvisioningWorkflowDisposition.ACCESS_ISOLATED, null, null)
    }

    fun delete(command: ProvisioningWorkflowCommand, ownerId: String = DEFAULT_OWNER): ProvisioningWorkflowResult {
        requireChange(command, PlanChange.DELETE)
        requireCanary(command)
        requireFreshEvidence(command.compilation)
        val subscriptionId = command.compilation.intent.subscriptionId
            ?: throw ConflictException("FIXED_SUBSCRIPTION_REQUIRED")
        val session = accessIsolation.observe(subscriptionId).also(::requireFreshSessionEvidence)
        if (session.activeSessionCount > 0) {
            if (!command.forceDisconnect) throw ConflictException("ACTIVE_SESSION_BLOCKS_REMOVAL")
            if (!command.forceDisconnectAuthorized) throw ConflictException("FORCE_DISCONNECT_FORBIDDEN")
            val disconnected = accessIsolation.disconnectActiveSessions(subscriptionId).also(::requireFreshSessionEvidence)
            if (disconnected.activeSessionCount > 0) throw ConflictException("ACTIVE_SESSION_DISCONNECT_FAILED")
        }
        val result = execute(planning.validateProduction(command.compilation), command, ownerId)
        if (result.execution?.status == com.duluin.ftth.provisioning.domain.model.ExecutionStatus.SUCCEEDED) {
            accessIsolation.terminate(subscriptionId)
        }
        return result
    }

    private fun execute(plan: ProvisionPlan, command: ProvisioningWorkflowCommand, ownerId: String): ProvisioningWorkflowResult {
        if (command.idempotencyKey.isBlank()) throw ConflictException("IDEMPOTENCY_KEY_REQUIRED")
        val execution = admission.admit(plan.id, command.idempotencyKey, command.affectedSubscriberIds.size)
        return ProvisioningWorkflowResult(
            ProvisioningWorkflowDisposition.EXECUTED,
            plan,
            executionRunner.run(execution.id, ownerId),
        )
    }

    private fun requireFreshEvidence(request: PlanCompilationRequest) {
        val timestamps = request.topology.map(PlanTopologyNode::observedAt) +
            request.capabilities.map(PlanCapability::observedAt) +
            request.observations.map(PlanObservation::observedAt)
        if (timestamps.any { !isFresh(it) }) throw ConflictException("STALE_WORKFLOW_EVIDENCE")
    }

    private fun requireFreshSessionEvidence(evidence: SubscriberSessionEvidence) {
        if (!isFresh(evidence.observedAt)) throw ConflictException("STALE_SESSION_EVIDENCE")
    }

    private fun isFresh(observedAt: Instant): Boolean {
        val now = clock.instant()
        return !observedAt.isAfter(now) && !observedAt.isBefore(now.minus(maximumEvidenceAge))
    }

    private fun requireChange(command: ProvisioningWorkflowCommand, required: PlanChange) {
        if (command.compilation.change != required) throw ConflictException("WORKFLOW_CHANGE_MISMATCH")
    }

    private fun requireCanary(command: ProvisioningWorkflowCommand) {
        if (command.compilation.intent.subjectId !in command.affectedSubscriberIds) {
            throw ConflictException("CANARY_SCOPE_EXCEEDED")
        }
        rollout.requireAutoApplyAllowed(command.affectedSubscriberIds.size)
    }

    private companion object {
        const val DEFAULT_OWNER = "provisioning-workflow"
    }
}

class EngineProvisioningExecutionRunner(
    private val engine: ProvisioningExecutionEngine,
) : ProvisioningExecutionRunner {
    override fun run(executionId: UUID, ownerId: String): ProvisionExecution = engine.run(executionId, ownerId)
}
