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
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
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
    // V180. Diikat saat permintaan dibuat, bukan saat diputuskan — lihat KDoc
    // `InventoryApprovalRequest.movementId`.
    @Column(updatable = false) var movementId: UUID?,
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

@Entity
@Table(name = "inventory_approval_effect")
class InventoryApprovalEffectJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var approvalId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var approvalType: InventoryApprovalType,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var status: InventoryApprovalStatus,
    @Column(updatable = false) var movementId: UUID?,
    @Column(nullable = false, updatable = false) var operationKey: String,
    @Column(nullable = false, updatable = false) var emittedAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_approval_delegation")
class InventoryApprovalDelegationJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var approverId: UUID,
    @Column(nullable = false, updatable = false) var delegateId: UUID,
    @Column(nullable = false) var validUntil: Instant,
) : TenantAwareJpaEntity(id)

interface InventoryApprovalJpaRepository : JpaRepository<InventoryApprovalJpaEntity, UUID> {
    // Kunci baris SENGAJA diambil saat membaca: dua approver yang menekan tombol pada detik yang
    // sama harus diserialisasi, kalau tidak keduanya membaca revisi lama dan tier yang sama bisa
    // disetujui dua kali.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): InventoryApprovalJpaEntity?

    fun findAllByTenantIdAndStatusOrderByRequestedAt(tenantId: UUID, status: InventoryApprovalStatus): List<InventoryApprovalJpaEntity>

    /**
     * ON CONFLICT DO NOTHING pada `(tenant_id, operation_key)` adalah SATU-SATUNYA penjaga
     * idempotensi yang benar di sini: pemeriksaan "cek dulu lalu simpan" di level aplikasi selalu
     * bisa disalip oleh instance lain di antara kedua langkahnya.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO inventory_approval (
                id, tenant_id, approval_type, amount, requester_id, custodian_id, movement_id, policy_version,
                policy_snapshot, policy_snapshot_hash, operation_key, operation_hash, emergency_reason,
                requested_at, expires_at, status, revision
            ) VALUES (
                :id, :tenantId, :approvalType, :amount, :requesterId, :custodianId, :movementId, :policyVersion,
                CAST(:policySnapshot AS jsonb), :policySnapshotHash, :operationKey, :operationHash, :emergencyReason,
                :requestedAt, :expiresAt, :status, :revision
            )
            ON CONFLICT (tenant_id, operation_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    @Suppress("LongParameterList")
    fun insertIfAbsent(
        id: UUID,
        tenantId: UUID,
        approvalType: String,
        amount: Long,
        requesterId: UUID,
        custodianId: UUID?,
        movementId: UUID?,
        policyVersion: Long,
        policySnapshot: String,
        policySnapshotHash: String,
        operationKey: String,
        operationHash: String,
        emergencyReason: String?,
        requestedAt: Instant,
        expiresAt: Instant,
        status: String,
        revision: Long,
    ): Int
}

interface InventoryApprovalDecisionJpaRepository : JpaRepository<InventoryApprovalDecisionJpaEntity, UUID> {
    @Query("select d from InventoryApprovalDecisionJpaEntity d where d.tenantId = :tenantId and d.approvalId = :approvalId order by d.revision")
    fun findAllForApproval(tenantId: UUID, approvalId: UUID): List<InventoryApprovalDecisionJpaEntity>
}

interface InventoryApprovalEffectJpaRepository : JpaRepository<InventoryApprovalEffectJpaEntity, UUID> {
    fun findAllByTenantIdOrderByEmittedAt(tenantId: UUID): List<InventoryApprovalEffectJpaEntity>

    /**
     * Efek persetujuan memicu mutasi stok sungguhan, jadi ia WAJIB terbit tepat satu kali per
     * approval. Basis data yang memutuskan pemenangnya lewat `inventory_approval_effect_uq`;
     * nilai balik 0 berarti instance lain sudah menerbitkannya dan kita tidak boleh mengulang.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO inventory_approval_effect (
                id, tenant_id, approval_id, approval_type, status, movement_id, operation_key, emitted_at
            ) VALUES (
                :id, :tenantId, :approvalId, :approvalType, :status, :movementId, :operationKey, :emittedAt
            )
            ON CONFLICT (tenant_id, approval_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    @Suppress("LongParameterList")
    fun insertIfAbsent(
        id: UUID,
        tenantId: UUID,
        approvalId: UUID,
        approvalType: String,
        status: String,
        movementId: UUID?,
        operationKey: String,
        emittedAt: Instant,
    ): Int
}

interface InventoryApprovalDelegationJpaRepository : JpaRepository<InventoryApprovalDelegationJpaEntity, UUID> {
    fun findAllByTenantId(tenantId: UUID): List<InventoryApprovalDelegationJpaEntity>
    // Tanpa tenantId: pemanggilnya hanya memegang pasangan approver/delegate, dan Hibernate
    // sudah menambahkan filter tenant dari @TenantId (diperkuat lagi oleh RLS di basis data).
    fun findByApproverIdAndDelegateId(approverId: UUID, delegateId: UUID): InventoryApprovalDelegationJpaEntity?
}
