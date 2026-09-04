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
import org.springframework.stereotype.Component
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inventory_approval")
class InventoryApprovalJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var approvalType: InventoryApprovalType,
    @Column(nullable = false, updatable = false) var amount: Long,
    @Column(nullable = false, updatable = false) var requesterId: UUID,
    @Column(updatable = false) var custodianId: UUID?,
    @Column(nullable = false, updatable = false) var policyVersion: Long,
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false) var policySnapshot: String,
    @Column(nullable = false, updatable = false) var policySnapshotHash: String,
    @Column(nullable = false, updatable = false) var operationKey: String,
    @Column(nullable = false, updatable = false) var operationHash: String,
    @Column(updatable = false) var emergencyReason: String?,
    @Column(nullable = false, updatable = false) var requestedAt: Instant,
    @Column(nullable = false, updatable = false) var expiresAt: Instant,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: InventoryApprovalStatus,
    @Column(nullable = false) var revision: Long,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_approval_decision")
class InventoryApprovalDecisionJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var approvalId: UUID,
    @Column(nullable = false, updatable = false) var tier: Int,
    @Column(nullable = false, updatable = false) var approverId: UUID,
    @Column(updatable = false) var delegatedFrom: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var decision: InventoryApprovalDecision,
    @Column(updatable = false) var reason: String?,
    @Column(nullable = false, updatable = false) var decidedAt: Instant,
    @Column(nullable = false, updatable = false) var revision: Long,
    @Column(nullable = false, updatable = false) var operationKey: String,
    @Column(nullable = false, updatable = false) var operationHash: String,
) : TenantAwareJpaEntity(id)

interface InventoryApprovalJpaRepository : JpaRepository<InventoryApprovalJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): InventoryApprovalJpaEntity?
}

interface InventoryApprovalDecisionJpaRepository : JpaRepository<InventoryApprovalDecisionJpaEntity, UUID> {
    @Query("select d from InventoryApprovalDecisionJpaEntity d where d.tenantId = :tenantId and d.approvalId = :approvalId order by d.revision")
    fun findAllForApproval(tenantId: UUID, approvalId: UUID): List<InventoryApprovalDecisionJpaEntity>
}

@Component
class InventoryApprovalPersistenceContract(
    val approvals: InventoryApprovalJpaRepository,
    val decisions: InventoryApprovalDecisionJpaRepository,
)
