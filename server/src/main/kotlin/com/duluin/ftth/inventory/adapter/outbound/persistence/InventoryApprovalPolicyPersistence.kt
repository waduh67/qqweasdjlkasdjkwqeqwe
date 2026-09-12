package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalAuditRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalPolicyRepository
import com.duluin.ftth.inventory.domain.model.ApprovalTierRule
import com.duluin.ftth.inventory.domain.model.EmergencyOverrideAudit
import com.duluin.ftth.inventory.domain.model.InventoryApprovalPolicyMatrix
import com.duluin.ftth.inventory.domain.model.InventoryApprovalType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inventory_approval_policy")
class InventoryApprovalPolicyJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32, updatable = false) var approvalType: InventoryApprovalType,
    @Column(nullable = false) var expirySeconds: Int,
    @Column(nullable = false) var emergencyAllowed: Boolean,
    @Column(nullable = false) var active: Boolean,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_approval_policy_tier")
class InventoryApprovalPolicyTierJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var policyId: UUID,
    @Column(nullable = false) var tierNumber: Int,
    @Column(nullable = false) var minimumAmount: Long,
    @Column(nullable = false, length = 120) var approverRole: String,
    /**
     * Dibaca lewat entity ini, tapi DITULIS lewat [InventoryApprovalPolicyTierJpaRepository.insertTier].
     *
     * Driver Postgres mengikat String sebagai `varchar`, dan INSERT varchar ke kolom jsonb
     * ditolak "column is of type jsonb but expression is of type character varying" —
     * kegagalan runtime, bukan saat build, karena `ddl-auto: none` membuat skema tidak
     * divalidasi saat boot. Membaca arah sebaliknya aman (jsonb keluar sebagai teks).
     * Pola CAST yang sama dipakai `inventory_approval.policy_snapshot` sejak V146.
     */
    @Column(nullable = false, columnDefinition = "jsonb", insertable = false, updatable = false) var approverIds: String,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_approval_emergency_audit")
class InventoryApprovalEmergencyAuditJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var approvalId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32, updatable = false) var approvalType: InventoryApprovalType,
    @Column(nullable = false, updatable = false) var amount: Long,
    @Column(nullable = false, updatable = false) var requesterId: UUID,
    @Column(updatable = false) var custodianId: UUID?,
    @Column(nullable = false, length = 500, updatable = false) var reason: String,
    // Sama seperti `approverIds`: ditulis lewat native insert ber-CAST, dibaca lewat entity.
    @Column(nullable = false, columnDefinition = "jsonb", insertable = false, updatable = false) var bypassedTiers: String,
    @Column(nullable = false, updatable = false) var occurredAt: Instant,
) : TenantAwareJpaEntity(id)

interface InventoryApprovalPolicyJpaRepository : JpaRepository<InventoryApprovalPolicyJpaEntity, UUID> {
    fun findByTenantIdAndApprovalTypeAndActiveIsTrue(tenantId: UUID, approvalType: InventoryApprovalType): InventoryApprovalPolicyJpaEntity?
    fun findAllByTenantIdAndActiveIsTrueOrderByApprovalType(tenantId: UUID): List<InventoryApprovalPolicyJpaEntity>
}

interface InventoryApprovalPolicyTierJpaRepository : JpaRepository<InventoryApprovalPolicyTierJpaEntity, UUID> {
    fun findAllByTenantIdAndPolicyIdOrderByTierNumber(tenantId: UUID, policyId: UUID): List<InventoryApprovalPolicyTierJpaEntity>

    @Query("select t from InventoryApprovalPolicyTierJpaEntity t where t.tenantId = :tenantId and t.policyId in :policyIds order by t.policyId, t.tierNumber")
    fun findAllForPolicies(tenantId: UUID, policyIds: Collection<UUID>): List<InventoryApprovalPolicyTierJpaEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM inventory_approval_policy_tier WHERE tenant_id = :tenantId AND policy_id = :policyId", nativeQuery = true)
    fun deleteForPolicy(tenantId: UUID, policyId: UUID): Int

    /** CAST eksplisit ke jsonb — lihat alasannya di [InventoryApprovalPolicyTierJpaEntity.approverIds]. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO inventory_approval_policy_tier (
                id, tenant_id, policy_id, tier_number, minimum_amount, approver_role, approver_ids
            ) VALUES (
                :id, :tenantId, :policyId, :tierNumber, :minimumAmount, :approverRole, CAST(:approverIds AS jsonb)
            )
        """,
        nativeQuery = true,
    )
    fun insertTier(
        id: UUID,
        tenantId: UUID,
        policyId: UUID,
        tierNumber: Int,
        minimumAmount: Long,
        approverRole: String,
        approverIds: String,
    ): Int
}

interface InventoryApprovalEmergencyAuditJpaRepository : JpaRepository<InventoryApprovalEmergencyAuditJpaEntity, UUID> {
    fun findAllByTenantIdOrderByOccurredAtDesc(tenantId: UUID): List<InventoryApprovalEmergencyAuditJpaEntity>

    /**
     * ON CONFLICT DO NOTHING, bukan "cek dulu lalu simpan": dua percobaan override darurat
     * dengan kunci operasi sama bisa jalan bersamaan, dan UNIQUE
     * `inventory_approval_emergency_audit_uq` yang memutuskan pemenangnya. Nilai balik 0
     * berarti jejaknya sudah ada — itu replay yang sah, bukan override kedua.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO inventory_approval_emergency_audit (
                id, tenant_id, approval_id, approval_type, amount, requester_id, custodian_id,
                reason, bypassed_tiers, occurred_at
            ) VALUES (
                :id, :tenantId, :approvalId, :approvalType, :amount, :requesterId, :custodianId,
                :reason, CAST(:bypassedTiers AS jsonb), :occurredAt
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
        amount: Long,
        requesterId: UUID,
        custodianId: UUID?,
        reason: String,
        bypassedTiers: String,
        occurredAt: Instant,
    ): Int
}

/**
 * Matriks persetujuan gudang dan jejak override daruratnya.
 *
 * Matriks disimpan RELASIONAL (tier sebagai baris), bukan satu kolom jsonb, karena ia
 * adalah data konfigurasi yang diedit manusia dan dijaga CHECK: "tier 1 wajib berambang 0"
 * dan "ambang naik seiring nomor tier" harus bisa ditolak basis data, bukan hanya kode.
 * Snapshot historis di `inventory_approval.policy_snapshot` tetap jsonb — itu rekaman beku,
 * bukan konfigurasi.
 */
@Component
class InventoryApprovalPolicyPersistenceAdapter(
    private val policies: InventoryApprovalPolicyJpaRepository,
    private val tiers: InventoryApprovalPolicyTierJpaRepository,
    private val audits: InventoryApprovalEmergencyAuditJpaRepository,
    private val mapper: ObjectMapper,
) : InventoryApprovalPolicyRepository, InventoryApprovalAuditRepository {

    override fun find(tenantId: UUID, type: InventoryApprovalType): InventoryApprovalPolicyMatrix? {
        val policy = policies.findByTenantIdAndApprovalTypeAndActiveIsTrue(tenantId, type) ?: return null
        return policy.toDomain(tiers.findAllByTenantIdAndPolicyIdOrderByTierNumber(tenantId, policy.id))
    }

    override fun findAll(tenantId: UUID): List<InventoryApprovalPolicyMatrix> {
        val rows = policies.findAllByTenantIdAndActiveIsTrueOrderByApprovalType(tenantId)
        if (rows.isEmpty()) return emptyList()
        // Satu query tier untuk semua kebijakan: layar pengaturan menampilkan seluruh jenis
        // sekaligus, dan versi per-baris melahirkan N+1 query yang tak ada gunanya.
        val byPolicy = tiers.findAllForPolicies(tenantId, rows.map { it.id }).groupBy { it.policyId }
        return rows.map { it.toDomain(byPolicy[it.id].orEmpty()) }
    }

    /**
     * Tier lama DIHAPUS seluruhnya lalu ditulis ulang, bukan digabung per nomor.
     *
     * Kalau digabung, menghapus tier kedua dari matriks tidak akan pernah berlaku: barisnya
     * tetap tinggal di tabel dan permintaan besar terus menuntut persetujuan kedua dari peran
     * yang sudah dihapus operator — permintaan menggantung sampai kedaluwarsa tanpa sebab
     * yang terlihat di layar pengaturan.
     */
    override fun save(matrix: InventoryApprovalPolicyMatrix): InventoryApprovalPolicyMatrix {
        val existing = policies.findByTenantIdAndApprovalTypeAndActiveIsTrue(matrix.tenantId, matrix.type)
        val policyId = if (existing == null) {
            val fresh = InventoryApprovalPolicyJpaEntity(
                UuidV7.generate(), matrix.type, matrix.expiry.seconds.toInt(), matrix.emergencyAllowed, true,
            )
            policies.save(fresh)
            fresh.id
        } else {
            existing.expirySeconds = matrix.expiry.seconds.toInt()
            existing.emergencyAllowed = matrix.emergencyAllowed
            policies.save(existing)
            tiers.deleteForPolicy(matrix.tenantId, existing.id)
            existing.id
        }
        matrix.tiers.sortedBy { it.number }.forEach { tier ->
            tiers.insertTier(
                UuidV7.generate(), matrix.tenantId, policyId, tier.number, tier.minimumAmount,
                tier.approverRole, mapper.writeValueAsString(tier.approverIds.map(UUID::toString)),
            )
        }
        // Dikembalikan apa adanya, BUKAN hasil baca ulang: `@TenantId` baru terisi saat INSERT
        // di-flush, jadi `tenantId!!` pada entity yang baru di-persist meledak NPE.
        return matrix
    }

    override fun recordEmergency(entry: EmergencyOverrideAudit): Boolean = audits.insertIfAbsent(
        UuidV7.generate(), entry.tenantId, entry.approvalId, entry.type.name, entry.amount,
        entry.requesterId, entry.custodianId, entry.reason,
        mapper.writeValueAsString(entry.bypassedTiers), entry.occurredAt,
    ) == 1

    override fun emergencyOverrides(tenantId: UUID): List<EmergencyOverrideAudit> =
        audits.findAllByTenantIdOrderByOccurredAtDesc(tenantId).map {
            EmergencyOverrideAudit(
                it.tenantId!!, it.approvalId, it.approvalType, it.amount, it.requesterId,
                it.custodianId, it.reason, readTiers(it.bypassedTiers), it.occurredAt,
            )
        }

    private fun InventoryApprovalPolicyJpaEntity.toDomain(rows: List<InventoryApprovalPolicyTierJpaEntity>) =
        InventoryApprovalPolicyMatrix(
            tenantId!!, approvalType, Duration.ofSeconds(expirySeconds.toLong()), emergencyAllowed,
            rows.map { row ->
                ApprovalTierRule(row.tierNumber, row.minimumAmount, row.approverRole, readIds(row.approverIds))
            },
            // Versi diturunkan dari waktu perubahan terakhir, bukan penghitung terpisah: satu
            // kolom penghitung berarti satu lagi hal yang bisa lupa dinaikkan, dan versi yang
            // macet membuat dua kebijakan berbeda tercatat dengan nomor sama di riwayat approval.
            version = maxOf(1L, updatedAt.epochSecond),
        )

    private fun readIds(json: String): Set<UUID> =
        mapper.readValue(json, Array<String>::class.java).map(UUID::fromString).toSet()

    private fun readTiers(json: String): List<Int> =
        mapper.readValue(json, Array<Int>::class.java).toList()
}
