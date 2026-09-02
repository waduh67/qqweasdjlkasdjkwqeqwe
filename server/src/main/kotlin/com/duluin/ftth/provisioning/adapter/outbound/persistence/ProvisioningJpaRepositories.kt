package com.duluin.ftth.provisioning.adapter.outbound.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import java.time.Instant
import com.duluin.ftth.provisioning.domain.model.AttemptStatus
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus

interface VlanPoolJpaRepository : JpaRepository<VlanPoolJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pool from VlanPoolJpaEntity pool where pool.id = :id")
    fun findLockedById(@Param("id") id: UUID): VlanPoolJpaEntity?
}
interface VlanReservedRangeJpaRepository : JpaRepository<VlanReservedRangeJpaEntity, UUID> {
    fun findByPoolId(poolId: UUID): List<VlanReservedRangeJpaEntity>
    fun deleteByPoolId(poolId: UUID)
}
interface SegmentProfileJpaRepository : JpaRepository<SegmentProfileJpaEntity, UUID>
interface ServiceIntentJpaRepository : JpaRepository<ServiceIntentJpaEntity, UUID>
interface VlanAllocationJpaRepository : JpaRepository<VlanAllocationJpaEntity, UUID> {
    fun findByPoolId(poolId: UUID): List<VlanAllocationJpaEntity>
    fun existsByDeviceKindAndDeviceIdAndVlanIdAndActiveTrue(
        deviceKind: com.duluin.ftth.provisioning.domain.model.DeviceKind,
        deviceId: UUID,
        vlanId: Int,
    ): Boolean
}
interface VlanAllocationReferenceJpaRepository : JpaRepository<VlanAllocationReferenceJpaEntity, UUID> {
    fun findByAllocationId(allocationId: UUID): List<VlanAllocationReferenceJpaEntity>
    fun deleteByAllocationId(allocationId: UUID)
}
interface ProvisionPlanJpaRepository : JpaRepository<ProvisionPlanJpaEntity, UUID> {
    fun findByIntentIdOrderByRevisionDesc(intentId: UUID): List<ProvisionPlanJpaEntity>
}
interface ProvisionStepJpaRepository : JpaRepository<ProvisionStepJpaEntity, UUID> {
    fun findByPlanIdOrderByStepOrder(planId: UUID): List<ProvisionStepJpaEntity>
    fun deleteByPlanId(planId: UUID)
}
interface ProvisionStepAttributeJpaRepository : JpaRepository<ProvisionStepAttributeJpaEntity, UUID> {
    fun findByStepIdIn(stepIds: Collection<UUID>): List<ProvisionStepAttributeJpaEntity>
    fun deleteByStepIdIn(stepIds: Collection<UUID>)
}
interface ProvisionExecutionJpaRepository : JpaRepository<ProvisionExecutionJpaEntity, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): ProvisionExecutionJpaEntity?
    fun findByIntentIdAndStatusIn(intentId: UUID, statuses: Collection<ExecutionStatus>): List<ProvisionExecutionJpaEntity>
}
interface DeviceLeaseJpaRepository : JpaRepository<DeviceLeaseJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lease from DeviceLeaseJpaEntity lease where lease.deviceKind = :kind and lease.deviceId = :deviceId")
    fun findLockedByDevice(
        @Param("kind") kind: com.duluin.ftth.provisioning.domain.model.DeviceKind,
        @Param("deviceId") deviceId: UUID,
    ): DeviceLeaseJpaEntity?
}
interface ExecutionStepJpaRepository : JpaRepository<ExecutionStepJpaEntity, UUID> {
    fun findByExecutionIdOrderByStepOrder(executionId: UUID): List<ExecutionStepJpaEntity>
}
interface StepAttemptJpaRepository : JpaRepository<StepAttemptJpaEntity, UUID> {
    fun findByExecutionStepIdOrderByAttemptNumber(executionStepId: UUID): List<StepAttemptJpaEntity>
    fun findByStatusOrderByStartedAt(status: AttemptStatus): List<StepAttemptJpaEntity>
    fun findByIdempotencyKey(idempotencyKey: String): List<StepAttemptJpaEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """update StepAttemptJpaEntity attempt set attempt.collectorId = :collectorId
           where attempt.id = :id and (attempt.collectorId is null or attempt.collectorId = :collectorId)""",
    )
    fun claimCollector(@Param("id") id: UUID, @Param("collectorId") collectorId: UUID): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """update StepAttemptJpaEntity attempt
           set attempt.status = :status, attempt.errorCode = :errorCode, attempt.completedAt = :completedAt
           where attempt.id = :id and attempt.status = com.duluin.ftth.provisioning.domain.model.AttemptStatus.DISPATCHED""",
    )
    fun completeIfDispatched(
        @Param("id") id: UUID,
        @Param("status") status: AttemptStatus,
        @Param("errorCode") errorCode: String?,
        @Param("completedAt") completedAt: Instant,
    ): Int
}
interface StepSnapshotJpaRepository : JpaRepository<StepSnapshotJpaEntity, UUID> {
    fun findByExecutionStepIdOrderByCapturedAt(executionStepId: UUID): List<StepSnapshotJpaEntity>
}
interface DeviceCircuitBreakerJpaRepository : JpaRepository<DeviceCircuitBreakerJpaEntity, UUID> {
    fun findByDeviceKindAndDeviceId(
        deviceKind: com.duluin.ftth.provisioning.domain.model.DeviceKind,
        deviceId: UUID,
    ): DeviceCircuitBreakerJpaEntity?
}
interface DeviceSnapshotJpaRepository : JpaRepository<DeviceSnapshotJpaEntity, UUID>
interface DeviceObservationJpaRepository : JpaRepository<DeviceObservationJpaEntity, UUID>
interface DriftRecordJpaRepository : JpaRepository<DriftRecordJpaEntity, UUID>
interface AdapterCertificationJpaRepository : JpaRepository<AdapterCertificationJpaEntity, UUID>
