package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.TenantCutoverSnapshot
import com.duluin.ftth.inventory.WarehouseCutoverState
import com.duluin.ftth.inventory.application.port.outbound.InventoryTenantPolicyRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inventory_tenant_cutover")
class InventoryTenantCutoverJpaEntity(
    id: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: WarehouseCutoverState,
    @Column(nullable = false) var epoch: Long,
    @Column(nullable = false) var activationAt: Instant,
    @Column(nullable = false) var initializationKind: String,
    var migrationBatchId: UUID? = null,
    @Column(columnDefinition = "text") var snapshotWatermark: String? = null,
    @Column(nullable = false, columnDefinition = "uuid[]") var pendingLegacyEffectIds: Array<UUID> = emptyArray(),
) : WarehouseVersionedEntity(id) {
    fun snapshot() = TenantCutoverSnapshot(requireNotNull(tenantId), state, epoch, migrationBatchId, snapshotWatermark)
}

@Repository
class InventoryTenantPolicyPersistence(private val entityManager: EntityManager) : InventoryTenantPolicyRepository {
    override fun read(): TenantCutoverSnapshot? = entityManager.createQuery(
        "select policy from InventoryTenantCutoverJpaEntity policy where policy.tenantId = :tenant", InventoryTenantCutoverJpaEntity::class.java,
    ).setParameter("tenant", TenantContext.tenantId()).resultList.singleOrNull()?.snapshot()

    override fun lock(exclusive: Boolean): TenantCutoverSnapshot? {
        entityManager.flush()
        val clause = if (exclusive) "FOR UPDATE" else "FOR SHARE"
        val rows = entityManager.createNativeQuery(
            "SELECT * FROM inventory_tenant_cutover WHERE tenant_id=:tenant $clause", InventoryTenantCutoverJpaEntity::class.java,
        ).setParameter("tenant", TenantContext.tenantId()).resultList
        val row = rows.singleOrNull() as InventoryTenantCutoverJpaEntity? ?: return null
        entityManager.refresh(row)
        return row.snapshot()
    }

    override fun initialize(newEmptyTenant: Boolean): TenantCutoverSnapshot {
        val state = if (newEmptyTenant) "ENFORCED" else "LEGACY"
        val kind = if (newEmptyTenant) "NEW_EMPTY" else "EXISTING"
        entityManager.createNativeQuery("""
            INSERT INTO inventory_tenant_cutover(id,tenant_id,state,initialization_kind,activation_at)
            VALUES (:id,:tenant,:state,:kind,warehouse_activation_at())
        """.trimIndent()).setParameter("id", UUID.randomUUID()).setParameter("tenant", TenantContext.tenantId())
            .setParameter("state", state).setParameter("kind", kind).executeUpdate()
        return requireNotNull(read())
    }

    override fun beginValidation(expectedEpoch: Long): TenantCutoverSnapshot {
        val changed = entityManager.createNativeQuery("""
            UPDATE inventory_tenant_cutover SET state='VALIDATING',epoch=epoch+1,revision=revision+1,
                migration_batch_id=:batch,snapshot_watermark=clock_timestamp()::text,
                pending_legacy_effect_ids=ARRAY(SELECT id FROM inventory_movement WHERE tenant_id=:tenant AND state<>'APPLIED' ORDER BY id),
                updated_at=clock_timestamp()
            WHERE tenant_id=:tenant AND epoch=:epoch AND state='LEGACY'
        """.trimIndent()).setParameter("batch", UUID.randomUUID()).setParameter("tenant", TenantContext.tenantId())
            .setParameter("epoch", expectedEpoch).executeUpdate()
        check(changed == 1) { "Locked cutover row changed unexpectedly" }
        return requireNotNull(lock(exclusive = true))
    }
}
