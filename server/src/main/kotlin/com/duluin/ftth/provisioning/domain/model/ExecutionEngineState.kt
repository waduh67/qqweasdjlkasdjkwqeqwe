package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ExecutionStepStatus {
    PENDING,
    PREFLIGHTED,
    APPLY_DISPATCHED,
    APPLIED,
    VERIFIED,
    COMPENSATING,
    COMPENSATED,
    FAILED,
}

enum class ExecutionPhase { PREFLIGHT, APPLY, VERIFY, ROLLBACK_CHECK, COMPENSATE, ROLLBACK_VERIFY }

enum class AttemptStatus { DISPATCHED, SUCCEEDED, TRANSIENT_FAILURE, PERMANENT_FAILURE }

enum class StepSnapshotKind { BEFORE, AFTER, ROLLBACK_CHECK, ROLLBACK_RESULT }

class ExecutionStep private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val executionId: UUID,
    val planStepId: UUID,
    val order: Int,
    val device: DeviceReference,
    status: ExecutionStepStatus,
    beforeHash: String?,
    afterHash: String?,
    lastError: String?,
) : ProvisioningAggregate {
    var status: ExecutionStepStatus = status
        private set
    var beforeHash: String? = beforeHash
        private set
    var afterHash: String? = afterHash
        private set
    var lastError: String? = lastError
        private set

    init {
        if (order < 1) throw ValidationException("EXECUTION_STEP_ORDER_INVALID")
        beforeHash?.requireSha256()
        afterHash?.requireSha256()
    }

    fun recordPreflight(hash: String) = apply {
        requireStatus(ExecutionStepStatus.PENDING)
        hash.requireSha256()
        beforeHash = hash
        status = ExecutionStepStatus.PREFLIGHTED
    }

    fun markApplyDispatched() = apply {
        if (status !in setOf(ExecutionStepStatus.PREFLIGHTED, ExecutionStepStatus.APPLY_DISPATCHED)) {
            throw ConflictException("ILLEGAL_EXECUTION_STEP_TRANSITION: $status -> APPLY_DISPATCHED")
        }
        status = ExecutionStepStatus.APPLY_DISPATCHED
    }

    fun recordApplied(hash: String) = apply {
        if (status !in setOf(ExecutionStepStatus.PREFLIGHTED, ExecutionStepStatus.APPLY_DISPATCHED)) {
            throw ConflictException("ILLEGAL_EXECUTION_STEP_TRANSITION: $status -> APPLIED")
        }
        hash.requireSha256()
        afterHash = hash
        status = ExecutionStepStatus.APPLIED
    }

    fun recordVerified(hash: String) = apply {
        if (status !in setOf(ExecutionStepStatus.PREFLIGHTED, ExecutionStepStatus.APPLIED)) {
            throw ConflictException("ILLEGAL_EXECUTION_STEP_TRANSITION: $status -> VERIFIED")
        }
        hash.requireSha256()
        afterHash = hash
        status = ExecutionStepStatus.VERIFIED
    }

    fun beginCompensation() = apply {
        requireStatus(ExecutionStepStatus.VERIFIED)
        status = ExecutionStepStatus.COMPENSATING
    }

    fun completeCompensation() = apply {
        requireStatus(ExecutionStepStatus.COMPENSATING)
        status = ExecutionStepStatus.COMPENSATED
    }

    fun fail(error: String) = apply {
        if (error.isBlank()) throw ValidationException("EXECUTION_STEP_ERROR_REQUIRED")
        status = ExecutionStepStatus.FAILED
        lastError = error
    }

    private fun requireStatus(expected: ExecutionStepStatus) {
        if (status != expected) throw ConflictException("ILLEGAL_EXECUTION_STEP_TRANSITION: $status")
    }

    companion object {
        fun pending(tenantId: UUID, executionId: UUID, planStepId: UUID, order: Int, device: DeviceReference) =
            ExecutionStep(UuidV7.generate(), tenantId, executionId, planStepId, order, device, ExecutionStepStatus.PENDING, null, null, null)

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            executionId: UUID,
            planStepId: UUID,
            order: Int,
            device: DeviceReference,
            status: ExecutionStepStatus,
            beforeHash: String?,
            afterHash: String?,
            lastError: String?,
        ) = ExecutionStep(id, tenantId, executionId, planStepId, order, device, status, beforeHash, afterHash, lastError)
    }
}

class StepAttempt private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val executionStepId: UUID,
    val phase: ExecutionPhase,
    val attemptNumber: Int,
    val idempotencyKey: String,
    val fencingToken: Long,
    val deadline: Instant,
    status: AttemptStatus,
    errorCode: String?,
    val startedAt: Instant,
    completedAt: Instant?,
) : ProvisioningAggregate {
    var status: AttemptStatus = status
        private set
    var errorCode: String? = errorCode
        private set
    var completedAt: Instant? = completedAt
        private set

    init {
        if (attemptNumber < 1) throw ValidationException("STEP_ATTEMPT_NUMBER_INVALID")
        if (idempotencyKey.isBlank() || idempotencyKey.length > 200) throw ValidationException("STEP_IDEMPOTENCY_KEY_INVALID")
        if (fencingToken < 1) throw ValidationException("STEP_FENCING_TOKEN_INVALID")
    }

    fun complete(outcome: AttemptStatus, errorCode: String?, at: Instant) = apply {
        if (status != AttemptStatus.DISPATCHED) throw ConflictException("STEP_ATTEMPT_ALREADY_TERMINAL")
        if (outcome == AttemptStatus.DISPATCHED) throw ValidationException("STEP_ATTEMPT_OUTCOME_INVALID")
        if (outcome == AttemptStatus.SUCCEEDED && errorCode != null) throw ValidationException("STEP_ATTEMPT_ERROR_INVALID")
        if (outcome != AttemptStatus.SUCCEEDED && errorCode.isNullOrBlank()) {
            throw ValidationException("STEP_ATTEMPT_ERROR_REQUIRED")
        }
        status = outcome
        this.errorCode = errorCode
        completedAt = at
    }

    companion object {
        fun dispatch(
            tenantId: UUID,
            executionStepId: UUID,
            phase: ExecutionPhase,
            attemptNumber: Int,
            idempotencyKey: String,
            fencingToken: Long,
            deadline: Instant,
            startedAt: Instant = Instant.now(),
        ) = StepAttempt(
            UuidV7.generate(), tenantId, executionStepId, phase, attemptNumber, idempotencyKey,
            fencingToken, deadline, AttemptStatus.DISPATCHED, null, startedAt, null,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            executionStepId: UUID,
            phase: ExecutionPhase,
            attemptNumber: Int,
            idempotencyKey: String,
            fencingToken: Long,
            deadline: Instant,
            status: AttemptStatus,
            errorCode: String?,
            startedAt: Instant,
            completedAt: Instant?,
        ) = StepAttempt(
            id, tenantId, executionStepId, phase, attemptNumber, idempotencyKey, fencingToken,
            deadline, status, errorCode, startedAt, completedAt,
        )
    }
}

data class StepSnapshot(
    override val id: UUID,
    val tenantId: UUID,
    val executionStepId: UUID,
    val kind: StepSnapshotKind,
    val stateHash: String,
    val state: NormalizedDeviceState,
    val capturedAt: Instant,
) : ProvisioningAggregate {
    init {
        stateHash.requireSha256()
    }

    companion object {
        fun capture(
            tenantId: UUID,
            executionStepId: UUID,
            kind: StepSnapshotKind,
            stateHash: String,
            state: NormalizedDeviceState,
            capturedAt: Instant = Instant.now(),
        ) = StepSnapshot(UuidV7.generate(), tenantId, executionStepId, kind, stateHash, state, capturedAt)
    }
}

data class DeviceLease(
    override val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    val executionId: UUID,
    val ownerId: String,
    val fencingToken: Long,
    val expiresAt: Instant,
) : ProvisioningAggregate

class DeviceCircuitBreaker private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    failureCount: Int,
    openUntil: Instant?,
) : ProvisioningAggregate {
    var failureCount: Int = failureCount
        private set
    var openUntil: Instant? = openUntil
        private set

    fun recordTransientFailure(at: Instant, threshold: Int, openDuration: Duration) = apply {
        failureCount += 1
        if (failureCount >= threshold) openUntil = at.plus(openDuration)
    }

    fun recordSuccess() = apply {
        failureCount = 0
        openUntil = null
    }

    fun isOpen(at: Instant): Boolean = openUntil?.isAfter(at) == true

    companion object {
        fun closed(tenantId: UUID, device: DeviceReference) =
            DeviceCircuitBreaker(UuidV7.generate(), tenantId, device, 0, null)

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            device: DeviceReference,
            failureCount: Int,
            openUntil: Instant?,
        ) = DeviceCircuitBreaker(id, tenantId, device, failureCount, openUntil)
    }
}

private fun String.requireSha256() {
    if (!matches(Regex("^[a-f0-9]{64}$"))) throw ValidationException("STATE_HASH_INVALID")
}
