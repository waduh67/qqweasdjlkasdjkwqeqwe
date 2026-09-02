package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.DeviceCircuitBreakerRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceLeaseRepository
import com.duluin.ftth.provisioning.application.port.outbound.ExecutionStepRepository
import com.duluin.ftth.provisioning.application.port.outbound.FencedExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepAttemptRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepSnapshotRepository
import com.duluin.ftth.provisioning.domain.model.DeviceCircuitBreaker
import com.duluin.ftth.provisioning.domain.model.DeviceLease
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionStep
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.StepAttempt
import com.duluin.ftth.provisioning.domain.model.StepSnapshot
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

private fun requiredTenant(entityTenantId: UUID?): UUID = entityTenantId ?: TenantContext.tenantId()

@Component
class FencedExecutionPersistenceAdapter(
    private val leases: DeviceLeaseJpaRepository,
) : FencedExecutionRepository {
    @Transactional
    override fun commitIfLeaseValid(
        tenantId: UUID,
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        fencingToken: Long,
        now: Instant,
        duration: Duration,
        write: () -> Boolean,
    ): Boolean {
        if (tenantId != TenantContext.tenantId() || ownerId.isBlank() || duration.isZero || duration.isNegative) return false
        val current = leases.findLockedByDevice(device.kind, device.id) ?: return false
        if (current.executionId != executionId || current.ownerId != ownerId || current.fencingToken != fencingToken) return false
        if (!current.expiresAt.isAfter(now)) return false
        current.expiresAt = now.plus(duration)
        leases.save(current)
        return write()
    }
}

@Component
class DeviceLeasePersistenceAdapter(
    private val leases: DeviceLeaseJpaRepository,
) : DeviceLeaseRepository {
    @Transactional
    override fun acquire(
        tenantId: UUID,
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        now: Instant,
        duration: Duration,
    ): DeviceLease? {
        require(ownerId.isNotBlank()) { "LEASE_OWNER_REQUIRED" }
        require(!duration.isZero && !duration.isNegative) { "LEASE_DURATION_INVALID" }
        val current = leases.findLockedByDevice(device.kind, device.id)
        if (current == null) {
            return leases.saveAndFlush(
                DeviceLeaseJpaEntity(UuidV7.generate(), device.kind, device.id, executionId, ownerId, 1, now.plus(duration)),
            ).toDomain()
        }
        if (current.executionId == executionId && current.ownerId == ownerId && current.expiresAt.isAfter(now)) {
            current.expiresAt = now.plus(duration)
            return leases.save(current).toDomain()
        }
        if (current.expiresAt.isAfter(now)) return null
        current.executionId = executionId
        current.ownerId = ownerId
        current.fencingToken += 1
        current.expiresAt = now.plus(duration)
        return leases.save(current).toDomain()
    }

    @Transactional
    override fun validateAndRenew(
        tenantId: UUID,
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        fencingToken: Long,
        now: Instant,
        duration: Duration,
    ): DeviceLease? {
        if (tenantId != TenantContext.tenantId() || ownerId.isBlank() || duration.isZero || duration.isNegative) return null
        val current = leases.findLockedByDevice(device.kind, device.id) ?: return null
        if (current.executionId != executionId || current.ownerId != ownerId || current.fencingToken != fencingToken) return null
        if (!current.expiresAt.isAfter(now)) return null
        current.expiresAt = now.plus(duration)
        return leases.saveAndFlush(current).toDomain()
    }

    @Transactional
    override fun release(
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        fencingToken: Long,
        now: Instant,
    ): Boolean {
        val current = leases.findLockedByDevice(device.kind, device.id) ?: return false
        if (current.executionId != executionId || current.ownerId != ownerId || current.fencingToken != fencingToken) return false
        current.expiresAt = now
        leases.save(current)
        return true
    }

    private fun DeviceLeaseJpaEntity.toDomain() = DeviceLease(
        id,
        requiredTenant(tenantId),
        DeviceReference(deviceKind, deviceId),
        executionId,
        ownerId,
        fencingToken,
        expiresAt,
    )
}

@Component
class ExecutionStepPersistenceAdapter(
    private val steps: ExecutionStepJpaRepository,
) : ExecutionStepRepository {
    override fun save(value: ExecutionStep): ExecutionStep {
        val entity = steps.findById(value.id).orElse(null)?.apply {
            status = value.status
            beforeHash = value.beforeHash
            afterHash = value.afterHash
            lastError = value.lastError
        } ?: ExecutionStepJpaEntity(
            value.id,
            value.executionId,
            value.planStepId,
            value.order,
            value.device.kind,
            value.device.id,
            value.status,
            value.beforeHash,
            value.afterHash,
            value.lastError,
        )
        return steps.save(entity).toDomain()
    }

    override fun findByExecutionId(executionId: UUID): List<ExecutionStep> =
        steps.findByExecutionIdOrderByStepOrder(executionId).map { it.toDomain() }

    private fun ExecutionStepJpaEntity.toDomain() = ExecutionStep.rehydrate(
        id,
        requiredTenant(tenantId),
        executionId,
        planStepId,
        stepOrder,
        DeviceReference(deviceKind, deviceId),
        status,
        beforeHash,
        afterHash,
        lastError,
    )
}

@Component
class StepAttemptPersistenceAdapter(
    private val attempts: StepAttemptJpaRepository,
) : StepAttemptRepository {
    override fun save(value: StepAttempt): StepAttempt {
        val entity = attempts.findById(value.id).orElse(null)?.apply {
            status = value.status
            errorCode = value.errorCode
            completedAt = value.completedAt
        } ?: StepAttemptJpaEntity(
            value.id,
            value.executionStepId,
            value.phase,
            value.attemptNumber,
            value.idempotencyKey,
            value.fencingToken,
            value.deadline,
            value.status,
            value.errorCode,
            value.startedAt,
            value.completedAt,
        )
        return attempts.save(entity).toDomain()
    }

    override fun findByExecutionStepId(executionStepId: UUID): List<StepAttempt> =
        attempts.findByExecutionStepIdOrderByAttemptNumber(executionStepId).map { it.toDomain() }

    override fun findById(id: UUID): StepAttempt? = attempts.findById(id).orElse(null)?.toDomain()

    @Transactional
    override fun completeIfDispatched(
        id: UUID,
        status: com.duluin.ftth.provisioning.domain.model.AttemptStatus,
        errorCode: String?,
        completedAt: Instant,
    ): Boolean {
        if (status == com.duluin.ftth.provisioning.domain.model.AttemptStatus.DISPATCHED) return false
        if (status == com.duluin.ftth.provisioning.domain.model.AttemptStatus.SUCCEEDED && errorCode != null) return false
        if (status != com.duluin.ftth.provisioning.domain.model.AttemptStatus.SUCCEEDED && errorCode.isNullOrBlank()) return false
        return attempts.completeIfDispatched(id, status, errorCode, completedAt) == 1
    }

    private fun StepAttemptJpaEntity.toDomain() = StepAttempt.rehydrate(
        id,
        requiredTenant(tenantId),
        executionStepId,
        phase,
        attemptNumber,
        idempotencyKey,
        fencingToken,
        deadline,
        status,
        errorCode,
        startedAt,
        completedAt,
    )
}

@Component
class StepSnapshotPersistenceAdapter(
    private val snapshots: StepSnapshotJpaRepository,
) : StepSnapshotRepository {
    override fun save(value: StepSnapshot): StepSnapshot = snapshots.save(
        StepSnapshotJpaEntity(
            value.id,
            value.executionStepId,
            value.kind,
            value.stateHash,
            value.state.values,
            value.capturedAt,
        ),
    ).toDomain()

    override fun findByExecutionStepId(executionStepId: UUID): List<StepSnapshot> =
        snapshots.findByExecutionStepIdOrderByCapturedAt(executionStepId).map { it.toDomain() }

    private fun StepSnapshotJpaEntity.toDomain() = StepSnapshot(
        id,
        requiredTenant(tenantId),
        executionStepId,
        snapshotKind,
        stateHash,
        NormalizedDeviceState.of(normalizedState),
        capturedAt,
    )
}

@Component
class DeviceCircuitBreakerPersistenceAdapter(
    private val circuits: DeviceCircuitBreakerJpaRepository,
) : DeviceCircuitBreakerRepository {
    override fun save(value: DeviceCircuitBreaker): DeviceCircuitBreaker {
        val entity = circuits.findByDeviceKindAndDeviceId(value.device.kind, value.device.id)?.apply {
            failureCount = value.failureCount
            openUntil = value.openUntil
        } ?: DeviceCircuitBreakerJpaEntity(
            value.id,
            value.device.kind,
            value.device.id,
            value.failureCount,
            value.openUntil,
        )
        return circuits.save(entity).toDomain()
    }

    override fun findByDevice(device: DeviceReference): DeviceCircuitBreaker? =
        circuits.findByDeviceKindAndDeviceId(device.kind, device.id)?.toDomain()

    private fun DeviceCircuitBreakerJpaEntity.toDomain() = DeviceCircuitBreaker.rehydrate(
        id,
        requiredTenant(tenantId),
        DeviceReference(deviceKind, deviceId),
        failureCount,
        openUntil,
    )
}
