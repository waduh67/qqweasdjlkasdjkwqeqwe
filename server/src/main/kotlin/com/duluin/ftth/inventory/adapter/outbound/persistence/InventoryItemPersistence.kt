package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.domain.model.InventoryItem
import com.duluin.ftth.inventory.domain.model.InventoryItemCategory
import com.duluin.ftth.inventory.domain.model.InventoryUnit
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "inventory_item")
class InventoryItemJpaEntity(
    id: UUID,
    // Kode adalah identitas bisnis item dan sudah dipakai ledger lama; mengubahnya akan
    // memutus jejak audit, jadi ditutup dari update di level pemetaan juga.
    @Column(nullable = false, length = 64, updatable = false) var code: String,
    @Column(nullable = false, length = 200) var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var category: InventoryItemCategory,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, updatable = false) var unit: InventoryUnit,
    @Column(nullable = false, updatable = false) var serialized: Boolean,
    @Column(nullable = false) var trackMac: Boolean,
    @Column var reorderPoint: BigDecimal?,
    @Column(nullable = false) var active: Boolean,
) : TenantAwareJpaEntity(id)

interface InventoryItemJpaRepository : JpaRepository<InventoryItemJpaEntity, UUID> {
    fun findAllByTenantIdOrderByCode(tenantId: UUID): List<InventoryItemJpaEntity>
    fun findByTenantIdAndCode(tenantId: UUID, code: String): InventoryItemJpaEntity?
}

@Component
class InventoryItemPersistenceAdapter(private val repository: InventoryItemJpaRepository) : InventoryItemRepository {
    override fun findById(id: UUID): InventoryItem? = repository.findById(id).orElse(null)?.toDomain()

    // Himpunan kosong di-short-circuit: `findAllById(emptyList())` tetap menembakkan satu query
    // `where id in ()` yang pasti kosong hasilnya. Penyaringan tenant TIDAK diulang di sini —
    // RLS + `@TenantId` sudah menutupnya di level baris.
    override fun findAllByIds(ids: Set<UUID>): List<InventoryItem> =
        if (ids.isEmpty()) emptyList() else repository.findAllById(ids).map { it.toDomain() }

    override fun findByCode(tenantId: UUID, code: String): InventoryItem? =
        repository.findByTenantIdAndCode(tenantId, code.trim().uppercase())?.toDomain()

    override fun findAll(tenantId: UUID): List<InventoryItem> = repository.findAllByTenantIdOrderByCode(tenantId).map { it.toDomain() }

    override fun save(item: InventoryItem): InventoryItem {
        val entity = repository.findById(item.id).orElse(null)
        if (entity == null) {
            repository.save(
                InventoryItemJpaEntity(
                    item.id, item.code, item.name, item.category, item.unit,
                    item.serialized, item.trackMac, item.reorderPoint, item.active,
                ),
            )
            return item
        }
        entity.name = item.name
        entity.category = item.category
        entity.trackMac = item.trackMac
        entity.reorderPoint = item.reorderPoint
        entity.active = item.active
        return repository.save(entity).toDomain()
    }

    private fun InventoryItemJpaEntity.toDomain() = InventoryItem.rehydrate(
        id, tenantId!!, code, name, category, unit, serialized, trackMac, reorderPoint, active,
    )
}
