package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.domain.model.*
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Component
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inventory_movement")
class InventoryMovementJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var operationNamespace: String,
    @Column(nullable = false, updatable = false) var operationKey: String,
    @Column(nullable = false, updatable = false) var payloadHash: String,
    @Column(nullable = false, updatable = false) var actorId: UUID,
    @Column(nullable = false, updatable = false) var reason: String,
    @Column(nullable = false, updatable = false) var serverReceivedAt: Instant,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var kind: MovementKind,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: MovementState,
    @Column(updatable = false) var compensatesMovementId: UUID?,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_movement_leg")
class InventoryMovementLegJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var movementId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var direction: LegDirection,
    @Column(nullable = false, updatable = false) var itemId: UUID,
    @Column(nullable = false, updatable = false) var skuId: UUID,
    @Column(nullable = false, updatable = false) var locationId: UUID,
    @Column(nullable = false, updatable = false) var quantity: Int,
    @Column(nullable = false, updatable = false) var serialized: Boolean,
    @Column(nullable = false, updatable = false) var custodyOwnerId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var custodyOwnerKind: OwnerKind,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var status: InventoryStatus,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_balance_projection")
class InventoryBalanceProjectionJpaEntity(
    id: UUID,
    @Column(nullable = false) var itemId: UUID,
    @Column(nullable = false) var skuId: UUID,
    @Column(nullable = false) var locationId: UUID,
    @Column(nullable = false) var custodyOwnerId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var custodyOwnerKind: OwnerKind,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: InventoryStatus,
    @Column(nullable = false) var quantity: Int,
    @Column(nullable = false) var rebuiltAt: Instant,
) : TenantAwareJpaEntity(id)

interface InventoryMovementJpaRepository : JpaRepository<InventoryMovementJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndOperationNamespaceAndOperationKey(tenantId: UUID, namespace: String, key: String): InventoryMovementJpaEntity?
}

interface InventoryMovementLegJpaRepository : JpaRepository<InventoryMovementLegJpaEntity, UUID>

interface InventoryBalanceProjectionJpaRepository : JpaRepository<InventoryBalanceProjectionJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InventoryBalanceProjectionJpaEntity p where p.tenantId = :tenantId and p.itemId = :itemId and p.locationId = :locationId")
    fun lockItem(tenantId: UUID, itemId: UUID, locationId: UUID): List<InventoryBalanceProjectionJpaEntity>
}

@Entity
@Table(name = "inventory_fulfillment_effect")
class InventoryFulfillmentEffectJpaEntity(
    id: UUID,
    @Column(name = "target_id", nullable = false, updatable = false) var targetId: UUID,
    @Column(name = "work_order_id", nullable = false, updatable = false) var workOrderId: UUID,
    @Column(name = "customer_id", nullable = false, updatable = false) var customerId: UUID,
    @Column(nullable = false, updatable = false) var namespace: String,
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String,
    @Column(name = "payload_hash", nullable = false, updatable = false) var payloadHash: String,
    @Column(name = "item_category", nullable = false, updatable = false) var itemCategory: String,
    @Column(nullable = false, updatable = false) var quantity: Int,
    @Column(nullable = false, updatable = false) var installed: Boolean,
    @Column(nullable = false, updatable = false) var returned: Boolean,
    @Column(name = "recorded_at", nullable = false, updatable = false) var recordedAt: Instant,
) : TenantAwareJpaEntity(id)

interface InventoryFulfillmentEffectJpaRepository : JpaRepository<InventoryFulfillmentEffectJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, key: String): InventoryFulfillmentEffectJpaEntity?

    @Modifying
    @Query(value = "INSERT INTO inventory_fulfillment_effect (id, tenant_id, target_id, work_order_id, customer_id, namespace, operation_key, payload_hash, item_category, quantity, installed, returned) VALUES (:id, :tenantId, :targetId, :workOrderId, :customerId, :namespace, :operationKey, :payloadHash, :itemCategory, :quantity, :installed, :returned) ON CONFLICT (tenant_id, namespace, operation_key) DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(id: UUID, tenantId: UUID, targetId: UUID, workOrderId: UUID, customerId: UUID, namespace: String, operationKey: String, payloadHash: String, itemCategory: String, quantity: Int, installed: Boolean, returned: Boolean): Int
}

@Component
class InventoryMovementPersistenceContract(
    val movements: InventoryMovementJpaRepository,
    val projections: InventoryBalanceProjectionJpaRepository,
)
