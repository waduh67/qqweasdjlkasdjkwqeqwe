package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.WorkOrderRecoveredAssetRepository
import com.duluin.ftth.inventory.domain.model.RecoveredAssetCondition
import com.duluin.ftth.inventory.domain.model.WorkOrderRecoveredAsset
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "work_order_recovered_asset")
class WorkOrderRecoveredAssetJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var workOrderId: UUID,
    @Column(nullable = false, updatable = false) var assetId: UUID,
    @Column(nullable = false, length = 128, updatable = false) var serialNumber: String,
    @Column(length = 32, updatable = false) var macAddress: String?,
    @Column(nullable = false, updatable = false) var itemId: UUID,
    @Column(nullable = false, length = 24, updatable = false) var itemCategory: String,
    @Column(nullable = false, updatable = false) var customerId: UUID,
    @Column(nullable = false, updatable = false) var technicianId: UUID,
    @Column(nullable = false, updatable = false) var technicianLocationId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var condition: RecoveredAssetCondition,
    @Column(length = 500) var note: String?,
    @Column(nullable = false, updatable = false) var recoveredAt: Instant,
    @Column(nullable = false, updatable = false) var recoveredBy: UUID,
    @Column var cancelledAt: Instant?,
    @Column var cancelledBy: UUID?,
    @Column(length = 500) var cancelReason: String?,
) : TenantAwareJpaEntity(id)

interface WorkOrderRecoveredAssetJpaRepository : JpaRepository<WorkOrderRecoveredAssetJpaEntity, UUID> {
    fun findAllByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId: UUID, workOrderId: UUID): List<WorkOrderRecoveredAssetJpaEntity>

    /**
     * SENGAJA memakai `CancelledAtIsNull`, bukan menyaring di memori: cermin persis dari indeks
     * parsial `work_order_recovered_asset_active_uq`. Kalau penyaringnya berbeda dari indeksnya,
     * pemeriksaan di muka akan meloloskan baris yang kemudian ditolak basis data (atau lebih
     * buruk: menolak baris yang sebenarnya sah).
     */
    fun findByTenantIdAndAssetIdAndCancelledAtIsNull(tenantId: UUID, assetId: UUID): WorkOrderRecoveredAssetJpaEntity?
}

@Component
class WorkOrderRecoveredAssetPersistenceAdapter(
    private val rows: WorkOrderRecoveredAssetJpaRepository,
) : WorkOrderRecoveredAssetRepository {

    override fun findByWorkOrder(tenantId: UUID, workOrderId: UUID): List<WorkOrderRecoveredAsset> =
        rows.findAllByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId, workOrderId).map { it.toDomain() }

    override fun findById(tenantId: UUID, id: UUID): WorkOrderRecoveredAsset? =
        rows.findById(id).orElse(null)?.takeIf { it.tenantId == tenantId }?.toDomain()

    override fun findActiveByAsset(tenantId: UUID, assetId: UUID): WorkOrderRecoveredAsset? =
        rows.findByTenantIdAndAssetIdAndCancelledAtIsNull(tenantId, assetId)?.toDomain()

    /**
     * Jalur INSERT mengembalikan objek domain yang DITERIMA, bukan `saved.toDomain()`.
     *
     * `@TenantId` baru diisi Hibernate saat INSERT-nya di-flush; membaca `tenantId!!` dari entity
     * yang baru disimpan melempar NPE. Pola yang sama dipakai di `WorkOrderMaterialPersistence.kt`.
     */
    override fun save(row: WorkOrderRecoveredAsset): WorkOrderRecoveredAsset {
        val entity = rows.findById(row.id).orElse(null)
        if (entity == null) {
            rows.save(
                WorkOrderRecoveredAssetJpaEntity(
                    row.id, row.workOrderId, row.assetId, row.serialNumber, row.macAddress,
                    row.itemId, row.itemCategory, row.customerId, row.technicianId,
                    row.technicianLocationId, row.condition, row.note, row.recoveredAt,
                    row.recoveredBy, row.cancelledAt, row.cancelledBy, row.cancelReason,
                ),
            )
            return row
        }
        // Hanya kolom yang memang boleh berubah. Identitas unit fisiknya (`assetId`, SN, MAC) dan
        // pemegangnya SENGAJA `updatable = false`: mengubahnya setelah saga berjalan berarti baris
        // yang sudah memotong saldo di satu dimensi tiba-tiba mengaku berasal dari dimensi lain.
        entity.condition = row.condition
        entity.note = row.note
        entity.cancelledAt = row.cancelledAt
        entity.cancelledBy = row.cancelledBy
        entity.cancelReason = row.cancelReason
        rows.save(entity)
        return row
    }

    private fun WorkOrderRecoveredAssetJpaEntity.toDomain() = WorkOrderRecoveredAsset(
        id, tenantId!!, workOrderId, assetId, serialNumber, macAddress, itemId, itemCategory,
        customerId, technicianId, technicianLocationId, condition, note, recoveredAt, recoveredBy,
        cancelledAt, cancelledBy, cancelReason,
    )
}
