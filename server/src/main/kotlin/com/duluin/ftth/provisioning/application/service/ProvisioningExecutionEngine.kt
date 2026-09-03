package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.DeviceCircuitBreakerRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceLeaseRepository
import com.duluin.ftth.provisioning.application.port.outbound.ExecutionStepRepository
import com.duluin.ftth.provisioning.application.port.outbound.FencedExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepAttemptRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepSnapshotRepository
import com.duluin.ftth.provisioning.domain.model.AttemptStatus
import com.duluin.ftth.provisioning.domain.model.DeviceCircuitBreaker
import com.duluin.ftth.provisioning.domain.model.DeviceLease
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.ExecutionStep
import com.duluin.ftth.provisioning.domain.model.ExecutionStepStatus
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.StepAttempt
import com.duluin.ftth.provisioning.domain.model.StepSnapshot
import com.duluin.ftth.provisioning.domain.model.StepSnapshotKind
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ExecutionPolicy(
    val maxAttempts: Int = 3,
    val attemptTimeout: Duration = Duration.ofSeconds(30),
    val initialBackoff: Duration = Duration.ofSeconds(1),
    val maximumBackoff: Duration = Duration.ofSeconds(30),
    val circuitFailureThreshold: Int = 1,
    val circuitOpenDuration: Duration = Duration.ofMinutes(1),
    val leaseDuration: Duration = Duration.ofSeconds(30),
) {
    init {
        require(maxAttempts > 0 && circuitFailureThreshold > 0) { "EXECUTION_POLICY_COUNT_INVALID" }
        require(listOf(attemptTimeout, initialBackoff, maximumBackoff, circuitOpenDuration, leaseDuration).none {
            it.isZero || it.isNegative
        }) { "EXECUTION_POLICY_DURATION_INVALID" }
    }
}

enum class DeviceFailureKind { TRANSIENT, PERMANENT, STALE_PRECONDITION, VERIFICATION_MISMATCH }

class DeviceOperationException(
    val code: String,
    val kind: DeviceFailureKind,
) : RuntimeException(code)

class SimulatedProcessCrash : RuntimeException("SIMULATED_PROCESS_CRASH")

data class DeviceStateObservation(
    val stateHash: String,
    val state: NormalizedDeviceState,
    val matchesDesired: Boolean,
)

data class DeviceApplyResult(
    val stateHash: String,
    val state: NormalizedDeviceState,
)

data class DispatchableProvisioningWork(
    val executionId: UUID,
    val planId: UUID,
    val revision: Int,
    val stepId: UUID,
    val device: DeviceReference,
    val operation: ProvisionOperation,
    val phase: ExecutionPhase,
    val idempotencyKey: String,
    val fencingToken: Long,
    val expectedPreconditionHash: String,
    val deadline: Instant,
    val attributes: Map<String, String>,
)

interface ProvisioningDeviceGateway {
    fun observe(work: DispatchableProvisioningWork): DeviceStateObservation
    fun apply(work: DispatchableProvisioningWork): DeviceApplyResult
    fun compensate(work: DispatchableProvisioningWork, before: NormalizedDeviceState): DeviceApplyResult
}

fun interface RetrySleeper {
    fun sleep(duration: Duration)
}

class ProvisioningExecutionEngine(
    private val plans: ProvisionPlanRepository,
    private val executions: ProvisionExecutionRepository,
    private val leases: DeviceLeaseRepository,
    private val fencedWrites: FencedExecutionRepository,
    private val executionSteps: ExecutionStepRepository,
    private val attempts: StepAttemptRepository,
    private val snapshots: StepSnapshotRepository,
    private val circuits: DeviceCircuitBreakerRepository,
    private val gateway: ProvisioningDeviceGateway,
    private val deviceIoExecutor: DeviceIoExecutor,
    private val safetyGate: ProvisioningSafetyGate,
    private val clock: Clock,
    private val sleeper: RetrySleeper,
    private val policy: ExecutionPolicy = ExecutionPolicy(),
) {
    fun enqueue(plan: ProvisionPlan, keySuffix: String): ProvisionExecution {
        if (plan.status != PlanStatus.VALIDATED) throw ValidationException("PLAN_NOT_VALIDATED")
        safetyGate.requireAllowed(
            plan,
            com.duluin.ftth.provisioning.domain.policy.ExecutionMode.PRODUCTION_AUTO_APPLY,
            SafetyGateScope.FORWARD,
        )
        val key = "${plan.intentId}:${plan.revision}:$keySuffix"
        executions.findByIdempotencyKey(key)?.let { return it }
        return executions.save(ProvisionExecution.queue(plan.tenantId, plan.intentId, plan.id, key))
    }

    fun run(executionId: UUID, ownerId: String): ProvisionExecution {
        val execution = executions.findById(executionId) ?: throw ValidationException("EXECUTION_NOT_FOUND")
        if (execution.status.isTerminal()) return execution
        val plan = plans.findById(execution.planId) ?: throw ValidationException("PLAN_NOT_FOUND")
        if (execution.status == ExecutionStatus.ROLLING_BACK) {
            val states = ensureExecutionSteps(execution, plan)
            compensate(execution, plan, states, ownerId, null)
            return executions.findById(execution.id)!!
        }
        if (plan.status != PlanStatus.VALIDATED) throw ValidationException("PLAN_NOT_VALIDATED")
        val persistedStates = executionSteps.findByExecutionId(execution.id).associateBy { it.planStepId }
        try {
            safetyGate.requireAllowed(
                plan,
                com.duluin.ftth.provisioning.domain.policy.ExecutionMode.PRODUCTION_AUTO_APPLY,
                SafetyGateScope.FORWARD,
            )
        } catch (denied: ValidationException) {
            if (persistedStates.values.any { it.status == ExecutionStepStatus.VERIFIED }) {
                execution.beginRollback()
                executions.save(execution)
                compensate(execution, plan, persistedStates, ownerId, null)
                return executions.findById(execution.id)!!
            }
            throw denied
        }
        val states = ensureExecutionSteps(execution, plan)
        if (execution.status == ExecutionStatus.QUEUED) {
            execution.start()
            executions.save(execution)
        }
        states.values.firstOrNull { it.status == ExecutionStepStatus.FAILED }?.let { failed ->
            val error = failed.lastError ?: "PERSISTED_STEP_FAILED"
            finishFailure(
                execution,
                plan,
                states,
                ownerId,
                StepFailure(
                    error,
                    false,
                    error in setOf("STALE_PRECONDITION", "VERIFICATION_MISMATCH", "DEADLINE_EXCEEDED"),
                    true,
                ),
            )
            return executions.findById(execution.id)!!
        }
        if (states.values.any { it.status in setOf(ExecutionStepStatus.COMPENSATING, ExecutionStepStatus.COMPENSATED) }) {
            execution.requireManualReconciliation("INCONSISTENT_COMPENSATION_STATE")
            return executions.save(execution)
        }

        for (planStep in plan.steps.sortedBy { it.order }) {
            val state = states.getValue(planStep.id)
            when (state.status) {
                ExecutionStepStatus.VERIFIED -> continue
                ExecutionStepStatus.PENDING,
                ExecutionStepStatus.PREFLIGHTED,
                ExecutionStepStatus.APPLY_DISPATCHED,
                ExecutionStepStatus.APPLIED,
                -> Unit
                ExecutionStepStatus.FAILED,
                ExecutionStepStatus.COMPENSATING,
                ExecutionStepStatus.COMPENSATED,
                -> throw ConflictException("UNRESOLVED_EXECUTION_STEP_STATUS: ${state.status}")
            }
            val lease = leases.acquire(
                execution.tenantId,
                planStep.device,
                execution.id,
                ownerId,
                clock.instant(),
                policy.leaseDuration,
            ) ?: return execution
            val failure = try {
                processStep(execution, plan, planStep, state, lease)
            } catch (denied: ValidationException) {
                val safetyFailure = StepFailure(denied.message ?: "SAFETY_POLICY_REJECTED", false, false, true)
                state.fail(safetyFailure.code)
                executionSteps.save(state)
                leases.release(planStep.device, execution.id, ownerId, lease.fencingToken, clock.instant())
                finishFailure(execution, plan, states, ownerId, safetyFailure)
                return executions.findById(execution.id)!!
            }
            if (failure == null) {
                leases.release(planStep.device, execution.id, ownerId, lease.fencingToken, clock.instant())
                continue
            }
            if (!failure.persistState) {
                leases.release(planStep.device, execution.id, ownerId, lease.fencingToken, clock.instant())
                return executions.findById(execution.id)!!
            }
            if (state.status != ExecutionStepStatus.FAILED) {
                state.fail(failure.code)
                executionSteps.save(state)
            }
            leases.release(planStep.device, execution.id, ownerId, lease.fencingToken, clock.instant())
            finishFailure(execution, plan, states, ownerId, failure)
            return executions.findById(execution.id)!!
        }

        execution.verify()
        executions.save(execution)
        execution.succeed()
        return executions.save(execution)
    }

    fun ingestAcknowledgement(attemptId: UUID, status: AttemptStatus, errorCode: String?): Boolean {
        val attempt = attempts.findById(attemptId) ?: return false
        val completedAt = clock.instant()
        if (status == AttemptStatus.SUCCEEDED && !attempt.deadline.isAfter(completedAt)) {
            return attempts.completeAcknowledgementIfCurrentLease(
                attemptId,
                AttemptStatus.TRANSIENT_FAILURE,
                "DEADLINE_EXCEEDED",
                completedAt,
                completedAt,
            )
        }
        return attempts.completeAcknowledgementIfCurrentLease(attemptId, status, errorCode, completedAt, completedAt)
    }

    private fun ensureExecutionSteps(execution: ProvisionExecution, plan: ProvisionPlan): Map<UUID, ExecutionStep> {
        val existing = executionSteps.findByExecutionId(execution.id).associateBy { it.planStepId }.toMutableMap()
        plan.steps.forEach { step ->
            existing.computeIfAbsent(step.id) {
                executionSteps.save(ExecutionStep.pending(execution.tenantId, execution.id, step.id, step.order, step.device))
            }
        }
        return existing
    }

    private fun processStep(
        execution: ProvisionExecution,
        plan: ProvisionPlan,
        planStep: ProvisionStep,
        state: ExecutionStep,
        lease: DeviceLease,
    ): StepFailure? {
        if (state.status == ExecutionStepStatus.PENDING) {
            var semanticFailure: StepFailure? = null
            val observed = executeIo(
                execution,
                plan,
                planStep,
                state,
                lease,
                ExecutionPhase.PREFLIGHT,
                operation = { gateway.observe(it).verifiedHash() },
                onSuccess = { observation ->
                snapshots.save(
                    StepSnapshot.capture(
                        execution.tenantId,
                        state.id,
                        StepSnapshotKind.BEFORE,
                        observation.stateHash,
                        observation.state,
                        clock.instant(),
                    ),
                )
                state.recordPreflight(observation.stateHash)
                if (observation.matchesDesired) {
                    recordVerified(execution, state, observation)
                } else if (observation.stateHash != planStep.preconditionHash) {
                    semanticFailure = StepFailure("STALE_PRECONDITION", false, true, true)
                    state.fail("STALE_PRECONDITION")
                    executionSteps.save(state)
                } else {
                    executionSteps.save(state)
                }
                },
                onTerminalFailure = { failure ->
                    state.fail(failure.code)
                    executionSteps.save(state)
                },
            )
            observed.failure?.let { return it }
            val observation = observed.value!!
            semanticFailure?.let { return it }
            if (observation.matchesDesired) return null
        }

        if (state.status == ExecutionStepStatus.APPLY_DISPATCHED) {
            var semanticFailure: StepFailure? = null
            val observed = executeIo(
                execution,
                plan,
                planStep,
                state,
                lease,
                ExecutionPhase.PREFLIGHT,
                operation = { gateway.observe(it).verifiedHash() },
                onSuccess = { observation ->
                if (observation.matchesDesired) {
                    attempts.findByExecutionStepId(state.id).lastOrNull {
                        it.phase == ExecutionPhase.APPLY && it.status == AttemptStatus.DISPATCHED
                    }?.let { pending ->
                        if (pending.deadline.isAfter(clock.instant())) {
                            attempts.completeIfDispatched(pending.id, AttemptStatus.SUCCEEDED, null, clock.instant())
                        } else {
                            attempts.completeIfDispatched(
                                pending.id,
                                AttemptStatus.TRANSIENT_FAILURE,
                                "DEADLINE_EXCEEDED",
                                clock.instant(),
                            )
                        }
                    }
                    state.recordApplied(observation.stateHash)
                    executionSteps.save(state)
                } else if (observation.stateHash != state.beforeHash) {
                    semanticFailure = StepFailure("STALE_PRECONDITION", false, true, true)
                    state.fail("STALE_PRECONDITION")
                    executionSteps.save(state)
                }
                },
                onTerminalFailure = { failure ->
                    state.fail(failure.code)
                    executionSteps.save(state)
                },
            )
            observed.failure?.let { return it }
            semanticFailure?.let { return it }
        }

        if (state.status == ExecutionStepStatus.PREFLIGHTED || state.status == ExecutionStepStatus.APPLY_DISPATCHED) {
            state.markApplyDispatched()
            executionSteps.save(state)
            val application = executeIo(
                execution,
                plan,
                planStep,
                state,
                lease,
                ExecutionPhase.APPLY,
                operation = { gateway.apply(it).verifiedHash() },
                onSuccess = { applied ->
                    state.recordApplied(applied.stateHash)
                    executionSteps.save(state)
                },
                onTerminalFailure = { failure ->
                    state.fail(failure.code)
                    executionSteps.save(state)
                },
            )
            application.failure?.let { return it }
        }

        if (state.status == ExecutionStepStatus.APPLIED) {
            var semanticFailure: StepFailure? = null
            val observed = executeIo(
                execution,
                plan,
                planStep,
                state,
                lease,
                ExecutionPhase.VERIFY,
                operation = { gateway.observe(it).verifiedHash() },
                onSuccess = { observation ->
                snapshots.save(
                    StepSnapshot.capture(
                        execution.tenantId,
                        state.id,
                        StepSnapshotKind.AFTER,
                        observation.stateHash,
                        observation.state,
                        clock.instant(),
                    ),
                )
                if (!observation.matchesDesired) {
                    recordCircuitFailure(planStep.device, execution.tenantId)
                    semanticFailure = StepFailure("VERIFICATION_MISMATCH", false, true, true)
                    state.fail("VERIFICATION_MISMATCH")
                    executionSteps.save(state)
                } else {
                    recordVerified(execution, state, observation)
                }
                },
                onTerminalFailure = { failure ->
                    state.fail(failure.code)
                    executionSteps.save(state)
                },
            )
            observed.failure?.let { return it }
            semanticFailure?.let { return it }
        }
        return null
    }

    private fun recordVerified(
        execution: ProvisionExecution,
        state: ExecutionStep,
        observation: DeviceStateObservation,
    ) {
        if (snapshots.findByExecutionStepId(state.id).none { it.kind == StepSnapshotKind.AFTER }) {
            snapshots.save(
                StepSnapshot.capture(
                    execution.tenantId,
                    state.id,
                    StepSnapshotKind.AFTER,
                    observation.stateHash,
                    observation.state,
                    clock.instant(),
                ),
            )
        }
        state.recordVerified(observation.stateHash)
        executionSteps.save(state)
    }

    private fun <T : Any> executeIo(
        execution: ProvisionExecution,
        plan: ProvisionPlan,
        planStep: ProvisionStep,
        state: ExecutionStep,
        lease: DeviceLease,
        phase: ExecutionPhase,
        operation: (DispatchableProvisioningWork) -> T,
        onSuccess: (T) -> Unit = {},
        onTerminalFailure: (StepFailure) -> Unit = {},
    ): IoResult<T> {
        var attemptNumber = attempts.findByExecutionStepId(state.id).count { it.phase == phase } + 1
        while (attemptNumber <= policy.maxAttempts) {
            val scope = phase.safetyScope()
            if (scope == SafetyGateScope.ROLLBACK) {
                safetyGate.requireStepAllowed(
                    plan,
                    planStep.id,
                    com.duluin.ftth.provisioning.domain.policy.ExecutionMode.PRODUCTION_AUTO_APPLY,
                    scope,
                )
            } else {
                safetyGate.requireAllowed(
                    plan,
                    com.duluin.ftth.provisioning.domain.policy.ExecutionMode.PRODUCTION_AUTO_APPLY,
                    scope,
                )
            }
            val activeLease = leases.validateAndRenew(
                execution.tenantId,
                planStep.device,
                execution.id,
                lease.ownerId,
                lease.fencingToken,
                clock.instant(),
                policy.leaseDuration,
            ) ?: return IoResult.failure(StepFailure("LEASE_LOST", false, false, false))
            val circuit = circuits.findByDevice(planStep.device)
            if (circuit?.isOpen(clock.instant()) == true) {
                val failure = StepFailure("CIRCUIT_OPEN", false, false, true)
                val committed = fencedWrites.commitIfLeaseValid(
                    execution.tenantId,
                    planStep.device,
                    execution.id,
                    activeLease.ownerId,
                    activeLease.fencingToken,
                    clock.instant(),
                    policy.leaseDuration,
                ) {
                    onTerminalFailure(failure)
                    true
                }
                return IoResult.failure(if (committed) failure else StepFailure("LEASE_LOST", false, false, false))
            }
            val deadline = clock.instant().plus(policy.attemptTimeout)
            val attempt = attempts.save(
                StepAttempt.dispatch(
                    execution.tenantId,
                    state.id,
                    phase,
                    attemptNumber,
                    idempotencyKey(execution.id, planStep.id, phase),
                    activeLease.fencingToken,
                    deadline,
                    clock.instant(),
                ),
            )
            val work = DispatchableProvisioningWork(
                execution.id,
                plan.id,
                plan.revision,
                planStep.id,
                planStep.device,
                planStep.operation,
                phase,
                attempt.idempotencyKey,
                activeLease.fencingToken,
                planStep.preconditionHash,
                deadline,
                planStep.attributes,
            )
            try {
                val result = deviceIoExecutor.execute(
                    "${execution.tenantId}:${planStep.device.kind}:${planStep.device.id}",
                    deadline,
                    renewalInterval = policy.leaseDuration.dividedBy(3),
                    renewLease = {
                        leases.validateAndRenew(
                            execution.tenantId,
                            planStep.device,
                            execution.id,
                            activeLease.ownerId,
                            activeLease.fencingToken,
                            clock.instant(),
                            policy.leaseDuration,
                        ) != null
                    },
                ) { operation(work) }
                val returnedAt = clock.instant()
                val late = !deadline.isAfter(returnedAt)
                val failure = StepFailure("DEADLINE_EXCEEDED", true, true, true)
                val committed = fencedWrites.commitIfLeaseValid(
                    execution.tenantId,
                    planStep.device,
                    execution.id,
                    activeLease.ownerId,
                    activeLease.fencingToken,
                    returnedAt,
                    policy.leaseDuration,
                ) {
                    val completed = if (late) {
                        attempts.completeIfDispatched(
                            attempt.id,
                            AttemptStatus.TRANSIENT_FAILURE,
                            "DEADLINE_EXCEEDED",
                            returnedAt,
                        )
                    } else {
                        attempts.completeIfDispatched(attempt.id, AttemptStatus.SUCCEEDED, null, returnedAt)
                    }
                    if (!completed) return@commitIfLeaseValid false
                    if (late) {
                        recordCircuitFailure(planStep.device, execution.tenantId)
                        onTerminalFailure(failure)
                    } else {
                        circuits.save(
                            (circuit ?: DeviceCircuitBreaker.closed(execution.tenantId, planStep.device)).recordSuccess(),
                        )
                        onSuccess(result)
                    }
                    true
                }
                if (!committed) return IoResult.failure(StepFailure("LEASE_LOST_OR_ACK_COMPLETED", false, false, false))
                if (late) return IoResult.failure(failure)
                return IoResult.success(result)
            } catch (_: DeviceIoLeaseLostException) {
                return IoResult.failure(StepFailure("LEASE_LOST", false, false, false))
            } catch (_: DeviceIoExclusionBusyException) {
                return IoResult.failure(StepFailure("DEVICE_IO_EXCLUSION_BUSY", false, false, false))
            } catch (_: DeviceIoCancellationPendingException) {
                val returnedAt = clock.instant()
                val cancellationFailure = StepFailure("DEVICE_IO_CANCELLATION_PENDING", false, true, true)
                val committed = fencedWrites.commitIfLeaseValid(
                    execution.tenantId,
                    planStep.device,
                    execution.id,
                    activeLease.ownerId,
                    activeLease.fencingToken,
                    returnedAt,
                    policy.leaseDuration,
                ) {
                    if (!attempts.completeIfDispatched(
                            attempt.id, AttemptStatus.PERMANENT_FAILURE, cancellationFailure.code, returnedAt,
                        )
                    ) return@commitIfLeaseValid false
                    onTerminalFailure(cancellationFailure)
                    true
                }
                return IoResult.failure(
                    if (committed) cancellationFailure else StepFailure("LEASE_LOST_OR_ACK_COMPLETED", false, false, false),
                )
            } catch (_: DeviceIoDeadlineExceededException) {
                val returnedAt = clock.instant()
                val deadlineFailure = StepFailure("DEADLINE_EXCEEDED", true, true, true)
                val committed = fencedWrites.commitIfLeaseValid(
                    execution.tenantId,
                    planStep.device,
                    execution.id,
                    activeLease.ownerId,
                    activeLease.fencingToken,
                    returnedAt,
                    policy.leaseDuration,
                ) {
                    if (!attempts.completeIfDispatched(
                            attempt.id, AttemptStatus.TRANSIENT_FAILURE, "DEADLINE_EXCEEDED", returnedAt,
                        )
                    ) return@commitIfLeaseValid false
                    recordCircuitFailure(planStep.device, execution.tenantId)
                    onTerminalFailure(deadlineFailure)
                    true
                }
                return IoResult.failure(
                    if (committed) deadlineFailure else StepFailure("LEASE_LOST_OR_ACK_COMPLETED", false, false, false),
                )
            } catch (failure: DeviceOperationException) {
                val returnedAt = clock.instant()
                val late = !deadline.isAfter(returnedAt)
                val retryable = failure.kind == DeviceFailureKind.TRANSIENT
                val opensCircuit = retryable || failure.kind == DeviceFailureKind.VERIFICATION_MISMATCH
                val willOpen = (circuit?.failureCount ?: 0) + 1 >= policy.circuitFailureThreshold
                val terminalFailure = when {
                    late -> StepFailure("DEADLINE_EXCEEDED", true, true, true)
                    !retryable -> StepFailure(failure.code, false, failure.kind != DeviceFailureKind.PERMANENT, true)
                    willOpen -> StepFailure("CIRCUIT_OPEN", true, false, true)
                    attemptNumber == policy.maxAttempts -> StepFailure("RETRY_EXHAUSTED", true, false, true)
                    else -> null
                }
                val committed = fencedWrites.commitIfLeaseValid(
                    execution.tenantId,
                    planStep.device,
                    execution.id,
                    activeLease.ownerId,
                    activeLease.fencingToken,
                    returnedAt,
                    policy.leaseDuration,
                ) {
                    val attemptStatus = if (late || retryable) {
                        AttemptStatus.TRANSIENT_FAILURE
                    } else {
                        AttemptStatus.PERMANENT_FAILURE
                    }
                    val errorCode = if (late) "DEADLINE_EXCEEDED" else failure.code
                    if (!attempts.completeIfDispatched(attempt.id, attemptStatus, errorCode, returnedAt)) {
                        return@commitIfLeaseValid false
                    }
                    if (late || opensCircuit) recordCircuitFailure(planStep.device, execution.tenantId)
                    terminalFailure?.let(onTerminalFailure)
                    true
                }
                if (!committed) return IoResult.failure(StepFailure("LEASE_LOST_OR_ACK_COMPLETED", false, false, false))
                terminalFailure?.let { return IoResult.failure(it) }
                sleeper.sleep(backoff(attemptNumber))
                attemptNumber += 1
            }
        }
        return IoResult.failure(StepFailure("RETRY_EXHAUSTED", true, false, true))
    }

    private fun finishFailure(
        execution: ProvisionExecution,
        plan: ProvisionPlan,
        states: Map<UUID, ExecutionStep>,
        ownerId: String,
        failure: StepFailure,
    ) {
        val verified = states.values.any { it.status == ExecutionStepStatus.VERIFIED }
        if (!verified) {
            execution.fail(failure.code)
            executions.save(execution)
            if (failure.manual) {
                execution.requireManualReconciliation(failure.code)
                executions.save(execution)
            }
            return
        }
        execution.beginRollback()
        executions.save(execution)
        compensate(execution, plan, states, ownerId, if (failure.manual) failure.code else null)
    }

    private fun compensate(
        execution: ProvisionExecution,
        plan: ProvisionPlan,
        states: Map<UUID, ExecutionStep>,
        ownerId: String,
        manualReasonAfterRollback: String?,
    ) {
        val planSteps = plan.steps.associateBy { it.id }
        val resumable = setOf(ExecutionStepStatus.VERIFIED, ExecutionStepStatus.COMPENSATING)
        for (state in states.values.filter { it.status in resumable }.sortedByDescending { it.order }) {
            val planStep = planSteps.getValue(state.planStepId)
            val lease = leases.acquire(
                execution.tenantId,
                state.device,
                execution.id,
                ownerId,
                clock.instant(),
                policy.leaseDuration,
            ) ?: return manual(execution, "LEASE_UNAVAILABLE_DURING_ROLLBACK")
            var intentionalCrash = false
            try {
                val before = snapshots.findByExecutionStepId(state.id).first { it.kind == StepSnapshotKind.BEFORE }
                var rollbackConflict = false
                var alreadyCompensated = false
                val rollbackCheck = executeIo(
                    execution,
                    plan,
                    planStep,
                    state,
                    lease,
                    ExecutionPhase.ROLLBACK_CHECK,
                    operation = { gateway.observe(it).verifiedHash() },
                    onSuccess = { current ->
                        snapshots.save(
                            StepSnapshot.capture(
                                execution.tenantId,
                                state.id,
                                StepSnapshotKind.ROLLBACK_CHECK,
                                current.stateHash,
                                current.state,
                                clock.instant(),
                            ),
                        )
                        when {
                            current.stateHash == before.stateHash -> {
                                if (state.status == ExecutionStepStatus.VERIFIED) state.beginCompensation()
                                state.completeCompensation()
                                executionSteps.save(state)
                                snapshots.save(
                                    StepSnapshot.capture(
                                        execution.tenantId,
                                        state.id,
                                        StepSnapshotKind.ROLLBACK_RESULT,
                                        current.stateHash,
                                        current.state,
                                        clock.instant(),
                                    ),
                                )
                                alreadyCompensated = true
                            }
                            current.stateHash != state.afterHash -> rollbackConflict = true
                            state.status == ExecutionStepStatus.VERIFIED -> {
                                state.beginCompensation()
                                executionSteps.save(state)
                            }
                        }
                    },
                )
                rollbackCheck.failure?.let {
                    if (!it.persistState) return
                    return manual(execution, it.code)
                }
                if (rollbackConflict) return manual(execution, "ROLLBACK_CONFLICT")
                if (alreadyCompensated) continue
                val compensation = executeIo(
                    execution,
                    plan,
                    planStep,
                    state,
                    lease,
                    ExecutionPhase.COMPENSATE,
                    operation = { gateway.compensate(it, before.state).verifiedHash() },
                )
                compensation.failure?.let {
                    if (!it.persistState) return
                    return manual(execution, it.code)
                }
                val result = compensation.value!!
                var rollbackMismatch = false
                val rollbackVerification = executeIo(
                    execution,
                    plan,
                    planStep,
                    state,
                    lease,
                    ExecutionPhase.ROLLBACK_VERIFY,
                    operation = { gateway.observe(it).verifiedHash() },
                    onSuccess = { verification ->
                        snapshots.save(
                            StepSnapshot.capture(
                                execution.tenantId,
                                state.id,
                                StepSnapshotKind.ROLLBACK_RESULT,
                                verification.stateHash,
                                verification.state,
                                clock.instant(),
                            ),
                        )
                        if (result.stateHash != before.stateHash || verification.stateHash != before.stateHash) {
                            rollbackMismatch = true
                        } else {
                            state.completeCompensation()
                            executionSteps.save(state)
                        }
                    },
                )
                rollbackVerification.failure?.let {
                    if (!it.persistState) return
                    return manual(execution, it.code)
                }
                if (rollbackMismatch) return manual(execution, "ROLLBACK_VERIFICATION_MISMATCH")
            } catch (denied: ValidationException) {
                return manual(execution, denied.message ?: "ROLLBACK_SAFETY_POLICY_REJECTED")
            } catch (crash: SimulatedProcessCrash) {
                intentionalCrash = true
                throw crash
            } finally {
                if (!intentionalCrash) {
                    leases.release(state.device, execution.id, ownerId, lease.fencingToken, clock.instant())
                }
            }
        }
        if (manualReasonAfterRollback != null) return manual(execution, manualReasonAfterRollback)
        execution.completeRollback()
        executions.save(execution)
    }

    private fun manual(execution: ProvisionExecution, code: String) {
        execution.requireManualReconciliation(code)
        executions.save(execution)
    }

    private fun recordCircuitFailure(device: DeviceReference, tenantId: UUID? = null): DeviceCircuitBreaker {
        val current = circuits.findByDevice(device)
            ?: DeviceCircuitBreaker.closed(tenantId ?: throw IllegalStateException("TENANT_REQUIRED"), device)
        return circuits.save(
            current.recordTransientFailure(clock.instant(), policy.circuitFailureThreshold, policy.circuitOpenDuration),
        )
    }

    private fun backoff(attemptNumber: Int): Duration {
        val multiplier = 1L shl (attemptNumber - 1).coerceAtMost(30)
        val candidate = policy.initialBackoff.multipliedBy(multiplier)
        return if (candidate > policy.maximumBackoff) policy.maximumBackoff else candidate
    }

    private fun idempotencyKey(executionId: UUID, stepId: UUID, phase: ExecutionPhase): String =
        "$executionId:$stepId:$phase"

    private fun DeviceStateObservation.verifiedHash(): DeviceStateObservation {
        if (stateHash != NormalizedStateHash.sha256(state)) {
            throw DeviceOperationException("STATE_HASH_MISMATCH", DeviceFailureKind.VERIFICATION_MISMATCH)
        }
        return this
    }

    private fun DeviceApplyResult.verifiedHash(): DeviceApplyResult {
        if (stateHash != NormalizedStateHash.sha256(state)) {
            throw DeviceOperationException("STATE_HASH_MISMATCH", DeviceFailureKind.VERIFICATION_MISMATCH)
        }
        return this
    }

    private fun ExecutionStatus.isTerminal(): Boolean = this in setOf(
        ExecutionStatus.SUCCEEDED,
        ExecutionStatus.ROLLED_BACK,
        ExecutionStatus.FAILED,
        ExecutionStatus.MANUAL_RECONCILIATION,
        ExecutionStatus.CANCELLED,
    )

    private fun ExecutionPhase.safetyScope(): SafetyGateScope = when (this) {
        ExecutionPhase.PREFLIGHT, ExecutionPhase.APPLY, ExecutionPhase.VERIFY -> SafetyGateScope.FORWARD
        ExecutionPhase.ROLLBACK_CHECK, ExecutionPhase.COMPENSATE, ExecutionPhase.ROLLBACK_VERIFY -> SafetyGateScope.ROLLBACK
    }

    private data class StepFailure(
        val code: String,
        val transient: Boolean,
        val manual: Boolean,
        val persistState: Boolean,
    )

    private class IoResult<T> private constructor(
        val value: T?,
        val failure: StepFailure?,
    ) {
        companion object {
            fun <T> success(value: T) = IoResult(value, null)
            fun <T> failure(failure: StepFailure) = IoResult<T>(null, failure)
        }
    }
}
