package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceCircuitBreaker
import com.duluin.ftth.provisioning.domain.model.DeviceLease
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ExecutionStep
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.StepAttempt
import com.duluin.ftth.provisioning.domain.model.StepSnapshot
import java.time.Duration
import java.time.Instant
import com.duluin.ftth.provisioning.domain.model.VlanPool
import java.util.UUID

interface SegmentProfileRepository { fun save(value: SegmentProfile): SegmentProfile; fun findById(id: UUID): SegmentProfile? }
interface VlanPoolRepository {
    fun save(value: VlanPool): VlanPool
    fun findById(id: UUID): VlanPool?
    fun findByIdForUpdate(id: UUID): VlanPool?
    fun lockDeviceAndFindActiveVlans(tenantId: UUID, device: DeviceReference): Set<Int>
}
interface ServiceIntentRepository { fun save(value: ServiceIntent): ServiceIntent; fun findById(id: UUID): ServiceIntent? }
interface ProvisionPlanRepository {
    fun save(value: ProvisionPlan): ProvisionPlan
    fun findById(id: UUID): ProvisionPlan?
    fun findLatestByIntentId(intentId: UUID): ProvisionPlan?
}
interface ProvisionExecutionRepository {
    fun save(value: ProvisionExecution): ProvisionExecution
    fun findById(id: UUID): ProvisionExecution?
    fun findByIdempotencyKey(key: String): ProvisionExecution?
}
interface DeviceLeaseRepository {
    fun acquire(
        tenantId: UUID,
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        now: Instant,
        duration: Duration,
    ): DeviceLease?
    fun validateAndRenew(
        tenantId: UUID,
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        fencingToken: Long,
        now: Instant,
        duration: Duration,
    ): DeviceLease?
    fun release(device: DeviceReference, executionId: UUID, ownerId: String, fencingToken: Long, now: Instant): Boolean
}
interface ExecutionStepRepository {
    fun save(value: ExecutionStep): ExecutionStep
    fun findByExecutionId(executionId: UUID): List<ExecutionStep>
}
interface StepAttemptRepository {
    fun save(value: StepAttempt): StepAttempt
    fun findById(id: UUID): StepAttempt?
    fun findByExecutionStepId(executionStepId: UUID): List<StepAttempt>
    fun completeIfDispatched(id: UUID, status: com.duluin.ftth.provisioning.domain.model.AttemptStatus, errorCode: String?, completedAt: Instant): Boolean
}
interface StepSnapshotRepository {
    fun save(value: StepSnapshot): StepSnapshot
    fun findByExecutionStepId(executionStepId: UUID): List<StepSnapshot>
}
interface DeviceCircuitBreakerRepository {
    fun save(value: DeviceCircuitBreaker): DeviceCircuitBreaker
    fun findByDevice(device: DeviceReference): DeviceCircuitBreaker?
}
interface FencedExecutionRepository {
    fun commitIfLeaseValid(
        tenantId: UUID,
        device: DeviceReference,
        executionId: UUID,
        ownerId: String,
        fencingToken: Long,
        now: Instant,
        duration: Duration,
        write: () -> Boolean,
    ): Boolean
}
interface DeviceSnapshotRepository { fun save(value: DeviceSnapshot): DeviceSnapshot; fun findById(id: UUID): DeviceSnapshot? }
interface DeviceObservationRepository { fun save(value: DeviceObservation): DeviceObservation; fun findById(id: UUID): DeviceObservation? }
interface DriftRecordRepository { fun save(value: DriftRecord): DriftRecord; fun findById(id: UUID): DriftRecord? }
interface AdapterCertificationRepository {
    fun save(value: AdapterCertification): AdapterCertification
    fun findById(id: UUID): AdapterCertification?
}
