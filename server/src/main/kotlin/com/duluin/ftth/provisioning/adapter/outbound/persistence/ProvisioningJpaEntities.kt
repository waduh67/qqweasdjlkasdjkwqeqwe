package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DriftStatus
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.IntentStatus
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.VlanEncapsulation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "provisioning_vlan_pool")
class VlanPoolJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 120) var name: String,
    @Column(name = "vlan_start", nullable = false) var vlanStart: Int,
    @Column(name = "vlan_end", nullable = false) var vlanEnd: Int,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_vlan_reserved_range")
class VlanReservedRangeJpaEntity(
    id: UUID,
    @Column(name = "pool_id", nullable = false, updatable = false) val poolId: UUID,
    @Column(name = "vlan_start", nullable = false) val vlanStart: Int,
    @Column(name = "vlan_end", nullable = false) val vlanEnd: Int,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_segment_profile")
class SegmentProfileJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 120) var name: String,
    @Column(name = "pool_id", nullable = false) var poolId: UUID,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_service_intent")
class ServiceIntentJpaEntity(
    id: UUID,
    @Column(name = "subscription_id", nullable = false, updatable = false) val subscriptionId: UUID,
    @Column(name = "segment_profile_id", nullable = false) var segmentProfileId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val encapsulation: VlanEncapsulation,
    @Column(name = "dedicated_vlan_id") val dedicatedVlanId: Int?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: IntentStatus,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_vlan_allocation")
class VlanAllocationJpaEntity(
    id: UUID,
    @Column(name = "pool_id", nullable = false, updatable = false) val poolId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "device_kind", nullable = false, updatable = false, length = 20)
    val deviceKind: DeviceKind,
    @Column(name = "device_id", nullable = false, updatable = false) val deviceId: UUID,
    @Column(name = "vlan_id", nullable = false, updatable = false) val vlanId: Int,
    @Column(name = "intent_id", nullable = false, updatable = false) val intentId: UUID,
    @Column(nullable = false) var active: Boolean,
    @Column(name = "reference_count", nullable = false, insertable = false, updatable = false) var referenceCount: Int = 0,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_vlan_allocation_reference")
class VlanAllocationReferenceJpaEntity(
    id: UUID,
    @Column(name = "allocation_id", nullable = false, updatable = false) val allocationId: UUID,
    @Column(name = "reference_kind", nullable = false, updatable = false, length = 40) val referenceKind: String,
    @Column(name = "reference_id", nullable = false, updatable = false) val referenceId: UUID,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_plan")
class ProvisionPlanJpaEntity(
    id: UUID,
    @Column(name = "intent_id", nullable = false, updatable = false) val intentId: UUID,
    @Column(nullable = false, updatable = false) val revision: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: PlanStatus,
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64) val contentHash: String,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_step")
class ProvisionStepJpaEntity(
    id: UUID,
    @Column(name = "plan_id", nullable = false, updatable = false) val planId: UUID,
    @Column(name = "step_order", nullable = false) val stepOrder: Int,
    @Enumerated(EnumType.STRING) @Column(name = "device_kind", nullable = false, length = 20) val deviceKind: DeviceKind,
    @Column(name = "device_id", nullable = false) val deviceId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) val operation: ProvisionOperation,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_step_attribute")
class ProvisionStepAttributeJpaEntity(
    id: UUID,
    @Column(name = "step_id", nullable = false, updatable = false) val stepId: UUID,
    @Column(name = "attribute_key", nullable = false, updatable = false, length = 80) val attributeKey: String,
    @Column(name = "attribute_value", nullable = false, length = 500) val attributeValue: String,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_execution")
class ProvisionExecutionJpaEntity(
    id: UUID,
    @Column(name = "plan_id", nullable = false, updatable = false) val planId: UUID,
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 160) val idempotencyKey: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var status: ExecutionStatus,
    @Column(length = 1000) var detail: String?,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_device_snapshot")
class DeviceSnapshotJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "device_kind", nullable = false, length = 20) val deviceKind: DeviceKind,
    @Column(name = "device_id", nullable = false) val deviceId: UUID,
    @Column(name = "plan_id", nullable = false) val planId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "normalized_state", nullable = false, columnDefinition = "jsonb")
    val normalizedState: Map<String, Any?>,
    @Column(name = "captured_at", nullable = false) val capturedAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_device_observation")
class DeviceObservationJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "device_kind", nullable = false, length = 20) val deviceKind: DeviceKind,
    @Column(name = "device_id", nullable = false) val deviceId: UUID,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "normalized_state", nullable = false, columnDefinition = "jsonb")
    val normalizedState: Map<String, Any?>,
    @Column(name = "observed_at", nullable = false) val observedAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_drift_record")
class DriftRecordJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "device_kind", nullable = false, length = 20) val deviceKind: DeviceKind,
    @Column(name = "device_id", nullable = false) val deviceId: UUID,
    @Column(name = "snapshot_id", nullable = false) val snapshotId: UUID,
    @Column(name = "observation_id", nullable = false) val observationId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val status: DriftStatus,
    @Column(name = "recorded_at", nullable = false) val recordedAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "provisioning_adapter_certification")
class AdapterCertificationJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "device_kind", nullable = false, length = 20) val deviceKind: DeviceKind,
    @Column(name = "device_id", nullable = false) val deviceId: UUID,
    @Column(nullable = false, length = 120) val model: String,
    @Column(nullable = false, length = 120) val firmware: String,
    @Column(nullable = false, length = 120) val transport: String,
    @Column(name = "operation_class", nullable = false, length = 120) val operationClass: String,
    @Column(name = "certified_at", nullable = false) val certifiedAt: Instant,
    @Column(name = "revoked_at") var revokedAt: Instant?,
) : TenantAwareJpaEntity(id)
