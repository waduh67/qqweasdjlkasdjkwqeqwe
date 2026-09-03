package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.integration.CollectorProvisioningChannel
import com.duluin.ftth.common.integration.ProvisioningDispatch
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.ProvisioningAcknowledgement
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.acknowledgementKey
import com.duluin.ftth.provisioning.domain.model.AttemptStatus
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.time.Clock

@Component
class ProvisioningCollectorChannelAdapter(
    private val attempts: StepAttemptJpaRepository,
    private val executionSteps: ExecutionStepJpaRepository,
    private val executions: ProvisionExecutionJpaRepository,
    private val plans: ProvisionPlanJpaRepository,
    private val planSteps: ProvisionStepJpaRepository,
    private val attributes: ProvisionStepAttributeJpaRepository,
    private val entityManager: EntityManager,
    private val capabilityEvidenceWriter: CollectorCapabilityEvidenceWriter,
    private val clock: Clock,
) : CollectorProvisioningChannel {
    @Transactional
    override fun pendingFor(
        collectorId: UUID,
        tenantId: UUID,
        availableTargetIds: Set<String>,
    ): List<ProvisioningDispatch> {
        requireTenant(tenantId)
        return attempts.findByStatusOrderByStartedAt(AttemptStatus.DISPATCHED).mapNotNull { attempt ->
            toDispatch(attempt, collectorId, availableTargetIds)
        }
    }

    @Transactional
    override fun accept(
        collectorId: UUID,
        tenantId: UUID,
        availableTargets: Map<String, ProvisioningTarget>,
        results: List<ProvisioningStepResult>,
        reports: List<DeviceCapabilityReport>,
    ): ProvisioningAcknowledgement {
        requireTenant(tenantId)
        val accepted = results.mapNotNull { result -> acknowledgeResult(collectorId, tenantId, result) }
        val reportKeys = reports.mapNotNull { report ->
            availableTargets[report.targetId]?.let { target ->
                capabilityEvidenceWriter.persist(OwnedCapabilityReport(collectorId, tenantId, target, report))
            }
        }
            .toSet()
        return ProvisioningAcknowledgement(
            resultIdempotencyKeys = accepted.filter { it.attemptId == null }.mapTo(linkedSetOf()) { it.idempotencyKey },
            resultAttemptIds = accepted.mapNotNullTo(linkedSetOf()) { it.attemptId },
            deviceReportKeys = reportKeys,
        )
    }

    private fun toDispatch(
        attempt: StepAttemptJpaEntity,
        collectorId: UUID,
        availableTargetIds: Set<String>,
    ): ProvisioningDispatch? {
        val executionStep = executionSteps.findById(attempt.executionStepId).orElse(null) ?: return null
        if (executionStep.deviceId.toString() !in availableTargetIds) return null
        if (attempt.collectorId != null && attempt.collectorId != collectorId) return null
        if (attempts.claimCollector(attempt.id, collectorId, executionStep.deviceId) != 1) return null
        val execution = executions.findById(executionStep.executionId).orElse(null) ?: return null
        val plan = plans.findById(execution.planId).orElse(null) ?: return null
        val planStep = planSteps.findById(executionStep.planStepId).orElse(null) ?: return null
        val values = attributes.findByStepIdIn(listOf(planStep.id)).associate { it.attributeKey to it.attributeValue }
        val payload = ProvisioningPayload(
            values.filterKeys { it !in setOf(ProvisionStep.PRECONDITION_HASH_ATTRIBUTE, ProvisionPlan.PLAN_PRECONDITION_HASH_ATTRIBUTE) },
        )
        return ProvisioningDispatch(
            planId = plan.id.toString(),
            revision = plan.revision,
            stepId = planStep.id.toString(),
            attemptId = attempt.id.toString(),
            phase = attempt.phase.toWirePhase(),
            operationClass = planStep.operation.name,
            idempotencyKey = attempt.idempotencyKey,
            fencingEpoch = attempt.fencingToken,
            expectedPreconditionHash = values[ProvisionStep.PRECONDITION_HASH_ATTRIBUTE],
            deadline = attempt.deadline,
            deviceId = executionStep.deviceId.toString(),
            deviceKind = executionStep.deviceKind.name,
            payload = payload,
        )
    }

    private fun acknowledgeResult(collectorId: UUID, tenantId: UUID, result: ProvisioningStepResult): AcceptedResult? {
        val candidates = result.attemptId?.let { rawId ->
            runCatching { UUID.fromString(rawId) }.getOrNull()?.let(attempts::findById)?.orElse(null)?.let(::listOf).orEmpty()
        } ?: attempts.findByIdempotencyKey(result.idempotencyKey)
        val context = candidates
            .mapNotNull(::contextFor)
            .singleOrNull { it.matches(collectorId, result) }
            ?: return null
        val outcome = result.toAttemptOutcome(context.attempt.deadline)
        val acceptedAt = clock.instant()
        val updated = attempts.completeAcknowledgementIfCurrentLease(
            context.attempt.id,
            outcome.status.name,
            outcome.errorCode,
            result.completedAt,
            acceptedAt,
        )
        val accepted = updated == 1 || (
            attempts.acknowledgementHasCurrentLease(context.attempt.id, acceptedAt) &&
                attempts.findById(context.attempt.id).orElse(null)?.let { stored ->
                    stored.status == outcome.status && stored.errorCode == outcome.errorCode
                } == true
            )
        if (!accepted) return null
        persistResult(collectorId, tenantId, context, result)
        return AcceptedResult(result.idempotencyKey, result.attemptId)
    }

    private fun persistResult(
        collectorId: UUID,
        tenantId: UUID,
        context: AttemptContext,
        result: ProvisioningStepResult,
    ) {
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_collector_result_receipt
                (id, tenant_id, collector_id, idempotency_key, plan_id, revision, step_id, attempt_id, target_id,
                operation_class, fencing_epoch, phase, success, completed_at, error_code, preflight_hash, apply_changed, apply_state_hash,
                verification_matches, verification_state_hash, managed_resource_count, rollback_success,
                rollback_state_hash, rollback_error_code)
               VALUES (:id, :tenant, :collector, :key, :plan, :revision, :step, :attempt, :target, :operation, :fence,
                :phase, :success, :completedAt, CAST(:errorCode AS varchar), CAST(:preflightHash AS varchar),
                CAST(:applyChanged AS boolean), CAST(:applyHash AS varchar), CAST(:verificationMatches AS boolean),
                CAST(:verificationHash AS varchar), CAST(:resourceCount AS integer), CAST(:rollbackSuccess AS boolean),
                CAST(:rollbackHash AS varchar), CAST(:rollbackError AS varchar))
               ON CONFLICT (tenant_id, attempt_id) DO NOTHING""",
        ).setParameter("id", UUID.randomUUID())
            .setParameter("tenant", tenantId)
            .setParameter("collector", collectorId)
            .setParameter("key", result.idempotencyKey)
            .setParameter("plan", result.planId)
            .setParameter("revision", result.revision)
            .setParameter("step", result.stepId)
            .setParameter("attempt", context.attempt.id)
            .setParameter("target", context.executionStep.deviceId.toString())
            .setParameter("operation", result.operationClass)
            .setParameter("fence", context.attempt.fencingToken)
            .setParameter("phase", result.phase.name)
            .setParameter("success", result.success)
            .setParameter("completedAt", result.completedAt)
            .setParameter("errorCode", result.errorCode?.name)
            .setParameter("preflightHash", result.preflight?.preconditionHash)
            .setParameter("applyChanged", result.apply?.changed)
            .setParameter("applyHash", result.apply?.resultingStateHash)
            .setParameter("verificationMatches", result.verification?.matchesExpected)
            .setParameter("verificationHash", result.verification?.stateHash)
            .setParameter(
                "resourceCount",
                result.verification?.state?.managedResourceCount ?: result.preflight?.state?.managedResourceCount,
            )
            .setParameter("rollbackSuccess", result.rollback?.success)
            .setParameter("rollbackHash", result.rollback?.resultingStateHash)
            .setParameter("rollbackError", result.rollback?.errorCode?.name)
            .executeUpdate()
    }

    private fun contextFor(attempt: StepAttemptJpaEntity): AttemptContext? {
        val executionStep = executionSteps.findById(attempt.executionStepId).orElse(null) ?: return null
        val execution = executions.findById(executionStep.executionId).orElse(null) ?: return null
        val plan = plans.findById(execution.planId).orElse(null) ?: return null
        val planStep = planSteps.findById(executionStep.planStepId).orElse(null) ?: return null
        return AttemptContext(attempt, executionStep, plan, planStep)
    }

    private fun requireTenant(tenantId: UUID) {
        require(TenantContext.tenantId() == tenantId) { "TENANT_OWNERSHIP_MISMATCH" }
    }

    private data class AttemptContext(
        val attempt: StepAttemptJpaEntity,
        val executionStep: ExecutionStepJpaEntity,
        val plan: ProvisionPlanJpaEntity,
        val step: ProvisionStepJpaEntity,
    ) {
        fun matches(collectorId: UUID, result: ProvisioningStepResult): Boolean {
            val fenceMatches = result.fencingEpoch == attempt.fencingToken ||
                (result.fencingEpoch == 0L && attempt.fencingToken == 1L && result.attemptId == null)
            val phaseMatches = when (attempt.phase) {
                ExecutionPhase.PREFLIGHT, ExecutionPhase.ROLLBACK_CHECK -> ProvisioningCommandPhase.PREFLIGHT
                ExecutionPhase.APPLY -> ProvisioningCommandPhase.APPLY
                ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY -> ProvisioningCommandPhase.VERIFY
                ExecutionPhase.COMPENSATE -> ProvisioningCommandPhase.ROLLBACK
            } == result.phase
            return attempt.collectorId == collectorId && fenceMatches &&
                result.idempotencyKey == attempt.idempotencyKey &&
                (result.attemptId == null || result.attemptId == attempt.id.toString()) &&
                (result.targetId == null || result.targetId == executionStep.deviceId.toString()) &&
                phaseMatches &&
                plan.id.toString() == result.planId && plan.revision == result.revision &&
                step.id.toString() == result.stepId && step.operation.name == result.operationClass
        }
    }

    private data class AcceptedResult(val idempotencyKey: String, val attemptId: String?)

    private data class AttemptOutcome(val status: AttemptStatus, val errorCode: String?)

    private fun ProvisioningStepResult.toAttemptOutcome(deadline: java.time.Instant): AttemptOutcome = when {
        success && completedAt.isBefore(deadline) -> AttemptOutcome(AttemptStatus.SUCCEEDED, null)
        success -> AttemptOutcome(AttemptStatus.TRANSIENT_FAILURE, "DEADLINE_EXCEEDED")
        errorCode == ProvisioningErrorCode.TIMEOUT -> AttemptOutcome(AttemptStatus.TRANSIENT_FAILURE, ProvisioningErrorCode.TIMEOUT.name)
        else -> AttemptOutcome(AttemptStatus.PERMANENT_FAILURE, requireNotNull(errorCode).name)
    }

    private fun ExecutionPhase.toWirePhase(): ProvisioningCommandPhase = when (this) {
        ExecutionPhase.PREFLIGHT, ExecutionPhase.ROLLBACK_CHECK -> ProvisioningCommandPhase.PREFLIGHT
        ExecutionPhase.APPLY -> ProvisioningCommandPhase.APPLY
        ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY -> ProvisioningCommandPhase.VERIFY
        ExecutionPhase.COMPENSATE -> ProvisioningCommandPhase.ROLLBACK
    }
}
