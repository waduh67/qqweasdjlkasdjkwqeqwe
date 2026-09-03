package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
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
import com.duluin.ftth.provisioning.domain.model.ActiveVlanAllocationPolicy
import com.duluin.ftth.provisioning.domain.model.AllocationReference
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
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
import jakarta.persistence.EntityManager
import java.util.UUID

private fun tenant(entityTenantId: UUID?): UUID = entityTenantId ?: TenantContext.tenantId()

private fun requireActiveTenant(entityTenantId: UUID) {
    if (TenantContext.tenantId() != entityTenantId) {
        throw ValidationException("TENANT_OWNERSHIP_MISMATCH")
    }
}

@Component
class SegmentProfilePersistenceAdapter(private val jpa: SegmentProfileJpaRepository) : SegmentProfileRepository {
    override fun save(value: SegmentProfile): SegmentProfile {
        requireActiveTenant(value.tenantId)
        val entity = jpa.findById(value.id).orElse(null)?.apply {
            name = value.name
            poolId = value.poolId
        } ?: SegmentProfileJpaEntity(value.id, value.name, value.poolId)
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): SegmentProfile? = jpa.findById(id).orElse(null)?.toDomain()
    override fun findAll(): List<SegmentProfile> = jpa.findAll().map { it.toDomain() }
    override fun deleteById(id: UUID) = jpa.deleteById(id)

    private fun SegmentProfileJpaEntity.toDomain() = SegmentProfile.rehydrate(id, tenant(tenantId), name, poolId)
}

@Component
class ServiceIntentPersistenceAdapter(private val jpa: ServiceIntentJpaRepository) : ServiceIntentRepository {
    override fun save(value: ServiceIntent): ServiceIntent {
        requireActiveTenant(value.tenantId)
        val entity = jpa.findById(value.id).orElse(null)?.apply {
            segmentProfileId = value.segmentProfileId
            status = value.status
        } ?: ServiceIntentJpaEntity(
            value.id,
            value.subscriptionId,
            value.hotspotSiteId,
            value.segmentProfileId,
            value.encapsulation,
            value.dedicatedVlanId,
            value.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): ServiceIntent? = jpa.findById(id).orElse(null)?.toDomain()
    override fun findAll(): List<ServiceIntent> = jpa.findAll().map { it.toDomain() }

    private fun ServiceIntentJpaEntity.toDomain() = ServiceIntent.rehydrate(
        id, tenant(tenantId), subscriptionId, hotspotSiteId, segmentProfileId, encapsulation, dedicatedVlanId, status,
    )

    override fun findBySubscriptionId(subscriptionId: UUID): ServiceIntent? =
        jpa.findBySubscriptionId(subscriptionId)?.toDomain()

    override fun findByHotspotSiteId(siteId: UUID): ServiceIntent? = jpa.findByHotspotSiteId(siteId)?.toDomain()
}

@Component
class VlanPoolPersistenceAdapter(
    private val pools: VlanPoolJpaRepository,
    private val reservedRanges: VlanReservedRangeJpaRepository,
    private val allocations: VlanAllocationJpaRepository,
    private val references: VlanAllocationReferenceJpaRepository,
    private val entityManager: EntityManager,
) : VlanPoolRepository {
    @Transactional
    override fun save(value: VlanPool): VlanPool {
        requireActiveTenant(value.tenantId)
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
            val existingAllocation = allocations.findById(allocation.id).orElse(null)
            val existingReferences = if (existingAllocation == null) emptyList() else references.findByAllocationId(allocation.id)
            val desiredReferences = allocation.references.map { it.kind to it.referenceId }.toSet()
            val staleReferences = existingReferences.filter { it.referenceKind to it.referenceId !in desiredReferences }
            if (staleReferences.isNotEmpty()) {
                references.deleteAll(staleReferences)
                references.flush()
            }
            if (existingAllocation == null && allocation.active) {
                val occupied = allocations.existsByDeviceKindAndDeviceIdAndVlanIdAndActiveTrue(
                    allocation.device.kind,
                    allocation.device.id,
                    allocation.vlanId,
                )
                if (occupied) {
                    ActiveVlanAllocationPolicy.requireAvailable(
                        value.tenantId,
                        allocation.device,
                        allocation.vlanId,
                        listOf(allocation),
                    )
                }
            }
            val entity = existingAllocation?.apply { active = allocation.active }
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
            val existingKeys = existingReferences.map { it.referenceKind to it.referenceId }.toSet()
            references.saveAll(allocation.references.filter { it.kind to it.referenceId !in existingKeys }.map {
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

    @Transactional
    override fun findByIdForUpdate(id: UUID): VlanPool? {
        pools.findLockedById(id) ?: return null
        return findById(id)
    }

    override fun findAll(): List<VlanPool> = pools.findAll().mapNotNull { findById(it.id) }

    override fun deleteById(id: UUID) = pools.deleteById(id)

    @Transactional
    override fun lockDeviceAndFindActiveVlans(tenantId: UUID, device: DeviceReference): Set<Int> {
        requireActiveTenant(tenantId)
        val scopeKey = "$tenantId:${device.kind.name}:${device.id}"
        entityManager.createNativeQuery(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:scopeKey AS text), 0))",
        ).setParameter("scopeKey", scopeKey)
            .singleResult
        return entityManager.createNativeQuery(
            """SELECT vlan_id
               FROM provisioning_vlan_allocation
               WHERE tenant_id = :tenantId
                 AND device_kind = :deviceKind
                 AND device_id = :deviceId
                 AND active = true
               ORDER BY vlan_id""",
        ).setParameter("tenantId", tenantId)
            .setParameter("deviceKind", device.kind.name)
            .setParameter("deviceId", device.id)
            .resultList
            .map { (it as Number).toInt() }
            .toSet()
    }
}

@Component
class ProvisionPlanPersistenceAdapter(
    private val plans: ProvisionPlanJpaRepository,
    private val steps: ProvisionStepJpaRepository,
    private val attributes: ProvisionStepAttributeJpaRepository,
    private val entityManager: EntityManager,
) : ProvisionPlanRepository {
    @Transactional
    override fun lockIntent(intentId: UUID) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
            .setParameter("lockKey", "${TenantContext.tenantId()}:provisioning-plan:$intentId")
            .singleResult
    }

    @Transactional
    override fun save(value: ProvisionPlan): ProvisionPlan {
        requireActiveTenant(value.tenantId)
        val existing = plans.findById(value.id).orElse(null)
        if (existing != null) {
            existing.status = value.status
            plans.save(existing)
        } else {
            plans.save(ProvisionPlanJpaEntity(value.id, value.intentId, value.revision, value.status, value.contentHash))
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

    @Transactional(readOnly = true)
    override fun findLatestByIntentId(intentId: UUID): ProvisionPlan? =
        plans.findByIntentIdOrderByRevisionDesc(intentId).firstOrNull()?.let { findById(it.id) }
}

@Component
class ProvisionExecutionPersistenceAdapter(
    private val jpa: ProvisionExecutionJpaRepository,
    private val entityManager: EntityManager,
) : ProvisionExecutionRepository {
    @Transactional
    override fun save(value: ProvisionExecution): ProvisionExecution {
        requireActiveTenant(value.tenantId)
        val existingById = jpa.findById(value.id).orElse(null)
        if (existingById != null) {
            val entity = existingById.apply {
                status = value.status
                detail = value.detail
            }
            return jpa.save(entity).toDomain()
        }
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_execution
               (id, tenant_id, intent_id, plan_id, idempotency_key, status, detail)
               VALUES (:id, :tenant, :intent, :plan, :key, :status, :detail)
               ON CONFLICT DO NOTHING""",
        ).setParameter("id", value.id)
            .setParameter("tenant", value.tenantId)
            .setParameter("intent", value.intentId)
            .setParameter("plan", value.planId)
            .setParameter("key", value.idempotencyKey)
            .setParameter("status", value.status.name)
            .setParameter("detail", value.detail)
            .executeUpdate()
        jpa.findByIdempotencyKey(value.idempotencyKey)?.let { persisted ->
            if (persisted.intentId != value.intentId || persisted.planId != value.planId) {
                throw ConflictException("EXECUTION_IDEMPOTENCY_KEY_REUSED")
            }
            return persisted.toDomain()
        }
        if (jpa.findByIntentIdAndStatusIn(value.intentId, ACTIVE_EXECUTION_STATUSES).isNotEmpty()) {
            throw ConflictException("ACTIVE_EXECUTION_EXISTS")
        }
        throw IllegalStateException("EXECUTION_IDEMPOTENCY_WRITE_LOST")
    }

    override fun findById(id: UUID): ProvisionExecution? = jpa.findById(id).orElse(null)?.toDomain()
    override fun findByIdempotencyKey(key: String): ProvisionExecution? = jpa.findByIdempotencyKey(key)?.toDomain()
    override fun findLatestByIntentId(intentId: UUID): ProvisionExecution? =
        jpa.findByIntentIdOrderByIdDesc(intentId).firstOrNull()?.toDomain()

    private fun ProvisionExecutionJpaEntity.toDomain() = ProvisionExecution.rehydrate(
        id, tenant(tenantId), intentId, planId, idempotencyKey, status, detail,
    )

    companion object {
        private val ACTIVE_EXECUTION_STATUSES = setOf(
            ExecutionStatus.QUEUED,
            ExecutionStatus.RUNNING,
            ExecutionStatus.VERIFYING,
            ExecutionStatus.ROLLING_BACK,
        )
    }
}

@Component
class DeviceSnapshotPersistenceAdapter(
    private val jpa: DeviceSnapshotJpaRepository,
    private val codec: NormalizedStateJsonCodec,
) : DeviceSnapshotRepository {
    override fun save(value: DeviceSnapshot): DeviceSnapshot {
        requireActiveTenant(value.tenantId)
        return jpa.save(value.toEntity()).toDomain()
    }
    override fun findById(id: UUID): DeviceSnapshot? = jpa.findById(id).orElse(null)?.toDomain()
    private fun DeviceSnapshot.toEntity() = DeviceSnapshotJpaEntity(
        id, device.kind, device.id, planId, codec.encode(state), capturedAt,
    )
    private fun DeviceSnapshotJpaEntity.toDomain() = DeviceSnapshot.rehydrate(
        id, tenant(tenantId), DeviceReference(deviceKind, deviceId), planId, codec.decode(normalizedState), capturedAt,
    )
}

@Component
class DeviceObservationPersistenceAdapter(
    private val jpa: DeviceObservationJpaRepository,
    private val codec: NormalizedStateJsonCodec,
) : DeviceObservationRepository {
    override fun save(value: DeviceObservation): DeviceObservation {
        requireActiveTenant(value.tenantId)
        return jpa.save(value.toEntity()).toDomain()
    }
    override fun findById(id: UUID): DeviceObservation? = jpa.findById(id).orElse(null)?.toDomain()
    private fun DeviceObservation.toEntity() = DeviceObservationJpaEntity(id, device.kind, device.id, codec.encode(state), observedAt)
    private fun DeviceObservationJpaEntity.toDomain() = DeviceObservation.rehydrate(
        id, tenant(tenantId), DeviceReference(deviceKind, deviceId), codec.decode(normalizedState), observedAt,
    )
}

@Component
class DriftRecordPersistenceAdapter(private val jpa: DriftRecordJpaRepository) : DriftRecordRepository {
    override fun save(value: DriftRecord): DriftRecord {
        requireActiveTenant(value.tenantId)
        return jpa.save(value.toEntity()).toDomain()
    }
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
        requireActiveTenant(value.tenantId)
        val entity = jpa.findById(value.id).orElse(null)?.apply {
            revokedAt = value.revokedAt
            revokedBy = value.revokedBy
        }
            ?: AdapterCertificationJpaEntity(
                value.id,
                value.device.kind,
                value.device.id,
                value.vendor,
                value.model,
                value.firmware,
                value.transport,
                value.operationClass,
                value.status,
                value.validUntil,
                value.evidenceId,
                value.certifiedBy,
                value.certifiedAt,
                value.revokedAt,
                value.revokedBy,
            )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): AdapterCertification? = jpa.findById(id).orElse(null)?.toDomain()
    override fun findAll(): List<AdapterCertification> = jpa.findAll().map { it.toDomain() }

    private fun AdapterCertificationJpaEntity.toDomain() = AdapterCertification.rehydrate(
        id,
        tenant(tenantId),
        DeviceReference(deviceKind, deviceId),
        vendor,
        model,
        firmware,
        transport,
        operationClass,
        status,
        validUntil,
        evidenceId,
        certifiedBy,
        certifiedAt,
        revokedAt,
        revokedBy,
    )
}
