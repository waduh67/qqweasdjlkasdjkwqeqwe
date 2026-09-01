package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.AdapterCertificationRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceSnapshotRepository
import com.duluin.ftth.provisioning.application.port.outbound.DriftRecordRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.SegmentProfileRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.AllocationReference
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.VlanAllocation
import com.duluin.ftth.provisioning.domain.model.VlanPool
import com.duluin.ftth.provisioning.domain.model.VlanRange
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private fun tenant(entityTenantId: UUID?): UUID = entityTenantId ?: TenantContext.tenantId()

@Component
class SegmentProfilePersistenceAdapter(private val jpa: SegmentProfileJpaRepository) : SegmentProfileRepository {
    override fun save(value: SegmentProfile): SegmentProfile {
        val entity = jpa.findById(value.id).orElse(null)?.apply {
            name = value.name
            poolId = value.poolId
        } ?: SegmentProfileJpaEntity(value.id, value.name, value.poolId)
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): SegmentProfile? = jpa.findById(id).orElse(null)?.toDomain()

    private fun SegmentProfileJpaEntity.toDomain() = SegmentProfile.rehydrate(id, tenant(tenantId), name, poolId)
}

@Component
class ServiceIntentPersistenceAdapter(private val jpa: ServiceIntentJpaRepository) : ServiceIntentRepository {
    override fun save(value: ServiceIntent): ServiceIntent {
        val entity = jpa.findById(value.id).orElse(null)?.apply {
            segmentProfileId = value.segmentProfileId
            status = value.status
        } ?: ServiceIntentJpaEntity(
            value.id,
            value.subscriptionId,
            value.segmentProfileId,
            value.encapsulation,
            value.dedicatedVlanId,
            value.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): ServiceIntent? = jpa.findById(id).orElse(null)?.toDomain()

    private fun ServiceIntentJpaEntity.toDomain() = ServiceIntent.rehydrate(
        id, tenant(tenantId), subscriptionId, segmentProfileId, encapsulation, dedicatedVlanId, status,
    )
}

@Component
class VlanPoolPersistenceAdapter(
    private val pools: VlanPoolJpaRepository,
    private val reservedRanges: VlanReservedRangeJpaRepository,
    private val allocations: VlanAllocationJpaRepository,
    private val references: VlanAllocationReferenceJpaRepository,
) : VlanPoolRepository {
    @Transactional
    override fun save(value: VlanPool): VlanPool {
        val pool = pools.findById(value.id).orElse(null)?.apply {
            name = value.name
            vlanStart = value.range.start
            vlanEnd = value.range.endInclusive
        } ?: VlanPoolJpaEntity(value.id, value.name, value.range.start, value.range.endInclusive)
        pools.save(pool)
        reservedRanges.deleteByPoolId(value.id)
        reservedRanges.saveAll(value.reservedRanges.map {
            VlanReservedRangeJpaEntity(UuidV7.generate(), value.id, it.start, it.endInclusive)
        })
        value.allocations.forEach { allocation ->
            val entity = allocations.findById(allocation.id).orElse(null)?.apply { active = allocation.active }
                ?: VlanAllocationJpaEntity(
                    allocation.id,
                    value.id,
                    allocation.device.kind,
                    allocation.device.id,
                    allocation.vlanId,
                    allocation.intentId,
                    allocation.active,
                )
            allocations.save(entity)
            references.deleteByAllocationId(allocation.id)
            references.saveAll(allocation.references.map {
                VlanAllocationReferenceJpaEntity(UuidV7.generate(), allocation.id, it.kind, it.referenceId)
            })
        }
        return findById(value.id)!!
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): VlanPool? {
        val pool = pools.findById(id).orElse(null) ?: return null
        val ranges = reservedRanges.findByPoolId(id).map { VlanRange(it.vlanStart, it.vlanEnd) }
        val domainAllocations = allocations.findByPoolId(id).map { allocation ->
            VlanAllocation.rehydrate(
                allocation.id,
                tenant(allocation.tenantId),
                allocation.poolId,
                DeviceReference(allocation.deviceKind, allocation.deviceId),
                allocation.vlanId,
                allocation.intentId,
                allocation.active,
                references.findByAllocationId(allocation.id).map {
                    AllocationReference(it.referenceKind, it.referenceId)
                },
            )
        }
        return VlanPool.rehydrate(
            pool.id,
            tenant(pool.tenantId),
            pool.name,
            VlanRange(pool.vlanStart, pool.vlanEnd),
            ranges,
            domainAllocations,
        )
    }
}

@Component
class ProvisionPlanPersistenceAdapter(
    private val plans: ProvisionPlanJpaRepository,
    private val steps: ProvisionStepJpaRepository,
    private val attributes: ProvisionStepAttributeJpaRepository,
) : ProvisionPlanRepository {
    @Transactional
    override fun save(value: ProvisionPlan): ProvisionPlan {
        val existing = plans.findById(value.id).orElse(null)
        val entity = existing?.apply { status = value.status } ?: ProvisionPlanJpaEntity(
            value.id, value.intentId, value.revision, value.status, value.contentHash,
        )
        plans.save(entity)
        if (value.status == com.duluin.ftth.provisioning.domain.model.PlanStatus.GENERATED) {
            val previousSteps = steps.findByPlanIdOrderByStepOrder(value.id)
            if (previousSteps.isNotEmpty()) attributes.deleteByStepIdIn(previousSteps.map { it.id })
            steps.deleteByPlanId(value.id)
            steps.saveAll(value.steps.map {
                ProvisionStepJpaEntity(it.id, value.id, it.order, it.device.kind, it.device.id, it.operation)
            })
            attributes.saveAll(value.steps.flatMap { step ->
                step.attributes.map { (key, attributeValue) ->
                    ProvisionStepAttributeJpaEntity(UuidV7.generate(), step.id, key, attributeValue)
                }
            })
        }
        return findById(value.id)!!
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ProvisionPlan? {
        val plan = plans.findById(id).orElse(null) ?: return null
        val entities = steps.findByPlanIdOrderByStepOrder(id)
        val byStep = attributes.findByStepIdIn(entities.map { it.id }).groupBy { it.stepId }
        val domainSteps = entities.map { step ->
            ProvisionStep.rehydrate(
                step.id,
                step.stepOrder,
                DeviceReference(step.deviceKind, step.deviceId),
                step.operation,
                byStep[step.id].orEmpty().associate { it.attributeKey to it.attributeValue },
            )
        }
        return ProvisionPlan.rehydrate(
            plan.id, tenant(plan.tenantId), plan.intentId, plan.revision, domainSteps, plan.status, plan.contentHash,
        )
    }
}

@Component
class ProvisionExecutionPersistenceAdapter(private val jpa: ProvisionExecutionJpaRepository) : ProvisionExecutionRepository {
    override fun save(value: ProvisionExecution): ProvisionExecution {
        val entity = jpa.findById(value.id).orElse(null)?.apply {
            status = value.status
            detail = value.detail
        } ?: ProvisionExecutionJpaEntity(value.id, value.planId, value.idempotencyKey, value.status, value.detail)
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): ProvisionExecution? = jpa.findById(id).orElse(null)?.toDomain()
    override fun findByIdempotencyKey(key: String): ProvisionExecution? = jpa.findByIdempotencyKey(key)?.toDomain()

    private fun ProvisionExecutionJpaEntity.toDomain() = ProvisionExecution.rehydrate(
        id, tenant(tenantId), planId, idempotencyKey, status, detail,
    )
}

@Component
class DeviceSnapshotPersistenceAdapter(private val jpa: DeviceSnapshotJpaRepository) : DeviceSnapshotRepository {
    override fun save(value: DeviceSnapshot): DeviceSnapshot = jpa.save(value.toEntity()).toDomain()
    override fun findById(id: UUID): DeviceSnapshot? = jpa.findById(id).orElse(null)?.toDomain()
    private fun DeviceSnapshot.toEntity() = DeviceSnapshotJpaEntity(
        id, device.kind, device.id, planId, state.values, capturedAt,
    )
    private fun DeviceSnapshotJpaEntity.toDomain() = DeviceSnapshot.rehydrate(
        id, tenant(tenantId), DeviceReference(deviceKind, deviceId), planId, NormalizedDeviceState.of(normalizedState), capturedAt,
    )
}

@Component
class DeviceObservationPersistenceAdapter(private val jpa: DeviceObservationJpaRepository) : DeviceObservationRepository {
    override fun save(value: DeviceObservation): DeviceObservation = jpa.save(value.toEntity()).toDomain()
    override fun findById(id: UUID): DeviceObservation? = jpa.findById(id).orElse(null)?.toDomain()
    private fun DeviceObservation.toEntity() = DeviceObservationJpaEntity(id, device.kind, device.id, state.values, observedAt)
    private fun DeviceObservationJpaEntity.toDomain() = DeviceObservation.rehydrate(
        id, tenant(tenantId), DeviceReference(deviceKind, deviceId), NormalizedDeviceState.of(normalizedState), observedAt,
    )
}

@Component
class DriftRecordPersistenceAdapter(private val jpa: DriftRecordJpaRepository) : DriftRecordRepository {
    override fun save(value: DriftRecord): DriftRecord = jpa.save(value.toEntity()).toDomain()
    override fun findById(id: UUID): DriftRecord? = jpa.findById(id).orElse(null)?.toDomain()
    private fun DriftRecord.toEntity() = DriftRecordJpaEntity(
        id, device.kind, device.id, snapshotId, observationId, status, recordedAt,
    )
    private fun DriftRecordJpaEntity.toDomain() = DriftRecord.rehydrate(
        id, tenant(tenantId), DeviceReference(deviceKind, deviceId), snapshotId, observationId, status, recordedAt,
    )
}

@Component
class AdapterCertificationPersistenceAdapter(
    private val jpa: AdapterCertificationJpaRepository,
) : AdapterCertificationRepository {
    override fun save(value: AdapterCertification): AdapterCertification {
        val entity = jpa.findById(value.id).orElse(null)?.apply { revokedAt = value.revokedAt }
            ?: AdapterCertificationJpaEntity(
                value.id,
                value.device.kind,
                value.device.id,
                value.model,
                value.firmware,
                value.transport,
                value.operationClass,
                value.certifiedAt,
                value.revokedAt,
            )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): AdapterCertification? = jpa.findById(id).orElse(null)?.toDomain()

    private fun AdapterCertificationJpaEntity.toDomain() = AdapterCertification.rehydrate(
        id,
        tenant(tenantId),
        DeviceReference(deviceKind, deviceId),
        model,
        firmware,
        transport,
        operationClass,
        certifiedAt,
        revokedAt,
    )
}
