package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.InventoryReconciliationRepository
import com.duluin.ftth.inventory.domain.model.CycleCount
import com.duluin.ftth.inventory.domain.model.DiscrepancyState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Hasil stock opname. Hanya `discrepancy_state`, `approver_id`, dan `closed_at` yang boleh
 * berubah: angka hitungan dan buktinya adalah rekaman apa yang DILIHAT petugas saat itu, dan
 * kalau angka itu bisa ditimpa maka seluruh gunanya sebagai kontrol kebocoran stok hilang.
 */
@Entity
@Table(name = "inventory_cycle_count")
class InventoryCycleCountJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var locationId: UUID,
    @Column(nullable = false, updatable = false) var itemId: UUID,
    @Column(nullable = false, updatable = false) var skuId: UUID,
    @Column(nullable = false, updatable = false) var priorQuantity: Int,
    @Column(nullable = false, updatable = false) var observedQuantity: Int,
    @Column(nullable = false, length = 500, updatable = false) var reason: String,
    @Column(nullable = false, length = 500, updatable = false) var evidenceReference: String,
    @Column(nullable = false, updatable = false) var custodianId: UUID,
    @Column var approverId: UUID?,
    @Column(nullable = false, length = 240, updatable = false) var operationKey: String,
    @Column(nullable = false, length = 128, updatable = false) var operationHash: String,
    @Enumerated(EnumType.STRING) @Column(name = "discrepancy_state", nullable = false, length = 24) var discrepancy: DiscrepancyState,
    @Column var closedAt: Instant?,
) : TenantAwareJpaEntity(id)

interface InventoryCycleCountJpaRepository : JpaRepository<InventoryCycleCountJpaEntity, UUID> {
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): InventoryCycleCountJpaEntity?

    fun findByTenantIdAndId(tenantId: UUID, id: UUID): InventoryCycleCountJpaEntity?

    @Query(
        """
        select c from InventoryCycleCountJpaEntity c
        where c.tenantId = :tenantId and c.discrepancy in :states
        order by c.createdAt, c.id
        """,
    )
    fun findByStates(tenantId: UUID, states: Collection<DiscrepancyState>): List<InventoryCycleCountJpaEntity>
}

@Component
class InventoryReconciliationPersistenceAdapter(
    private val counts: InventoryCycleCountJpaRepository,
) : InventoryReconciliationRepository {

    override fun save(count: CycleCount): CycleCount {
        val existing = counts.findById(count.countId).orElse(null)
        if (existing == null) {
            val saved = counts.save(
                InventoryCycleCountJpaEntity(
                    count.countId, count.locationId, count.itemId, count.skuId, count.priorQuantity,
                    count.observedQuantity, count.reason, count.evidenceReference, count.custodianId,
                    count.approverId, count.operationKey, count.operationHash, count.discrepancy, count.closedAt,
                ),
            )
            // JANGAN `saved.toDomain()` di jalur sisipan: Hibernate baru mengisi `@TenantId` saat
            // INSERT-nya di-flush, sehingga instance yang baru saja di-persist masih memegang
            // tenantId null dan `tenantId!!` meledak NPE. Tenant-nya toh sudah dipegang pemanggil.
            // `createdAt` diambil dari entity supaya nilai yang dikembalikan sama persis dengan
            // yang tersimpan, bukan stempel waktu kedua yang meleset beberapa milidetik.
            return count.copy(createdAt = saved.createdAt)
        }
        existing.discrepancy = count.discrepancy
        existing.approverId = count.approverId
        existing.closedAt = count.closedAt
        return counts.save(existing).toDomain()
    }

    override fun findById(countId: UUID): CycleCount? = counts.findById(countId).orElse(null)?.toDomain()

    override fun find(tenantId: UUID, countId: UUID): CycleCount? = counts.findByTenantIdAndId(tenantId, countId)?.toDomain()

    override fun findByOperation(tenantId: UUID, operationKey: String): CycleCount? =
        counts.findByTenantIdAndOperationKey(tenantId, operationKey)?.toDomain()

    // "Terbuka" mencakup REWORK_REQUIRED: selisih yang ditolak approver tetap butuh tindak
    // lanjut, dan kalau ia hilang dari daftar kerja maka barang yang kurang tidak akan pernah
    // dicari lagi.
    override fun findOpen(tenantId: UUID): List<CycleCount> =
        counts.findByStates(tenantId, listOf(DiscrepancyState.OPEN, DiscrepancyState.REWORK_REQUIRED)).map { it.toDomain() }

    private fun InventoryCycleCountJpaEntity.toDomain() = CycleCount(
        id, tenantId!!, locationId, itemId, skuId, priorQuantity, observedQuantity, reason,
        evidenceReference, operationKey, operationHash, custodianId, createdAt, discrepancy,
        approverId, closedAt,
    )
}
