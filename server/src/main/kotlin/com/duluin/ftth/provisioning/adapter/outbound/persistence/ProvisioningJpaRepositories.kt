package com.duluin.ftth.provisioning.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface VlanPoolJpaRepository : JpaRepository<VlanPoolJpaEntity, UUID>
interface VlanReservedRangeJpaRepository : JpaRepository<VlanReservedRangeJpaEntity, UUID> {
    fun findByPoolId(poolId: UUID): List<VlanReservedRangeJpaEntity>
    fun deleteByPoolId(poolId: UUID)
}
interface SegmentProfileJpaRepository : JpaRepository<SegmentProfileJpaEntity, UUID>
interface ServiceIntentJpaRepository : JpaRepository<ServiceIntentJpaEntity, UUID>
interface VlanAllocationJpaRepository : JpaRepository<VlanAllocationJpaEntity, UUID> {
    fun findByPoolId(poolId: UUID): List<VlanAllocationJpaEntity>
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
}
interface DeviceSnapshotJpaRepository : JpaRepository<DeviceSnapshotJpaEntity, UUID>
interface DeviceObservationJpaRepository : JpaRepository<DeviceObservationJpaEntity, UUID>
interface DriftRecordJpaRepository : JpaRepository<DriftRecordJpaEntity, UUID>
interface AdapterCertificationJpaRepository : JpaRepository<AdapterCertificationJpaEntity, UUID>
