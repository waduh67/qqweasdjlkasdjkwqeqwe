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
    // V174: jembatan ke `inventory_serialized_asset`. Tanpa dua kolom ini, saldo kuantitas
    // dan daftar aset per unit tak pernah bisa direkonsiliasi satu sama lain.
    @Column(updatable = false) var assetId: UUID? = null,
    @Column(length = 128, updatable = false) var serialNumber: String? = null,
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

    @Query("select m from InventoryMovementJpaEntity m where m.tenantId = :tenantId order by m.serverReceivedAt, m.id")
    fun findAllForTenant(tenantId: UUID): List<InventoryMovementJpaEntity>

    /**
     * Sisipan yang aman terhadap lomba: UNIQUE (tenant_id, operation_namespace, operation_key)
     * yang memutuskan pemenangnya, bukan pemeriksaan baca-dulu di aplikasi. ON CONFLICT DO
     * NOTHING SENGAJA dipakai alih-alih membiarkan constraint melempar, supaya pihak yang
     * kalah bisa membaca baris pemenang dan memperlakukannya sebagai replay.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """INSERT INTO inventory_movement
                   (id, tenant_id, operation_namespace, operation_key, payload_hash, actor_id, reason,
                    server_received_at, kind, state, compensates_movement_id)
                   VALUES (:id, :tenantId, :namespace, :operationKey, :payloadHash, :actorId, :reason,
                           :serverReceivedAt, :kind, :state, :compensatesMovementId)
                   ON CONFLICT (tenant_id, operation_namespace, operation_key) DO NOTHING""",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        id: UUID,
        tenantId: UUID,
        namespace: String,
        operationKey: String,
        payloadHash: String,
        actorId: UUID,
        reason: String,
        serverReceivedAt: Instant,
        kind: String,
        state: String,
        compensatesMovementId: UUID?,
    ): Int
}

interface InventoryMovementLegJpaRepository : JpaRepository<InventoryMovementLegJpaEntity, UUID> {
    fun findAllByTenantIdAndMovementIdOrderByIdAsc(tenantId: UUID, movementId: UUID): List<InventoryMovementLegJpaEntity>

    @Query("select l from InventoryMovementLegJpaEntity l where l.tenantId = :tenantId and l.movementId in :movementIds order by l.id")
    fun findAllForMovements(tenantId: UUID, movementIds: Collection<UUID>): List<InventoryMovementLegJpaEntity>
}

interface InventoryBalanceProjectionJpaRepository : JpaRepository<InventoryBalanceProjectionJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InventoryBalanceProjectionJpaEntity p where p.tenantId = :tenantId and p.itemId = :itemId and p.locationId = :locationId")
    fun lockItem(tenantId: UUID, itemId: UUID, locationId: UUID): List<InventoryBalanceProjectionJpaEntity>

    @Query("select p from InventoryBalanceProjectionJpaEntity p where p.tenantId = :tenantId and p.quantity <> 0")
    fun findNonZeroForTenant(tenantId: UUID): List<InventoryBalanceProjectionJpaEntity>

    /**
     * Siapkan baris dimensi saldo kalau belum ada, SELALU dengan kuantitas 0.
     *
     * SENGAJA dipisah dari penerapan delta, dan SENGAJA tidak memakai
     * `ON CONFLICT DO UPDATE SET quantity = quantity + EXCLUDED.quantity`:
     * PostgreSQL mengevaluasi CHECK pada baris CALON insert SEBELUM mendeteksi konflik unique.
     * Artinya delta negatif (setiap barang keluar) langsung ditolak
     * `inventory_balance_projection_quantity_check` walaupun saldo yang tersimpan jauh lebih
     * besar dari jumlah yang dikeluarkan — gudang tidak akan pernah bisa mencatat pengeluaran.
     * Menyisipkan 0 selalu lolos CHECK, lalu [applyDelta] yang menggerakkan angkanya.
     *
     * Dimensinya PERSIS mengikuti UNIQUE `inventory_balance_dimension_uq` (tanpa sku_id) —
     * kalau tidak, ON CONFLICT tidak punya index yang cocok dan setiap mutasi akan melahirkan
     * baris saldo baru alih-alih menambah yang lama.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """INSERT INTO inventory_balance_projection
                   (id, tenant_id, item_id, sku_id, location_id, custody_owner_id, custody_owner_kind, status, quantity, rebuilt_at)
                   VALUES (:id, :tenantId, :itemId, :skuId, :locationId, :ownerId, :ownerKind, :status, 0, :at)
                   ON CONFLICT (tenant_id, item_id, location_id, custody_owner_id, custody_owner_kind, status)
                   DO NOTHING""",
        nativeQuery = true,
    )
    fun ensureDimension(
        id: UUID,
        tenantId: UUID,
        itemId: UUID,
        skuId: UUID,
        locationId: UUID,
        ownerId: UUID,
        ownerKind: String,
        status: String,
        at: Instant,
    ): Int

    /**
     * Terapkan delta pada dimensi yang sudah dipastikan ada oleh [ensureDimension].
     *
     * Di sinilah CHECK `quantity >= 0` menjalankan tugas aslinya: ia dievaluasi pada hasil
     * penjumlahan, jadi pengeluaran yang melebihi stok tetap DITOLAK di level database.
     * Itu jaring pengaman terakhir di belakang validasi aplikasi — kalau keduanya jebol,
     * saldo minus berarti gudang mengaku mengeluarkan barang yang tak pernah dimilikinya.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """UPDATE inventory_balance_projection
                   SET quantity = quantity + :delta, rebuilt_at = :at, updated_at = now()
                   WHERE tenant_id = :tenantId AND item_id = :itemId AND location_id = :locationId
                     AND custody_owner_id = :ownerId AND custody_owner_kind = :ownerKind
                     AND status = :status""",
        nativeQuery = true,
    )
    fun applyDelta(
        tenantId: UUID,
        itemId: UUID,
        locationId: UUID,
        ownerId: UUID,
        ownerKind: String,
        status: String,
        delta: Int,
        at: Instant,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM inventory_balance_projection WHERE tenant_id = :tenantId", nativeQuery = true)
    fun deleteAllForTenant(tenantId: UUID): Int

    /**
     * Hitung ulang proyeksi dari leg mutasi APPLIED.
     *
     * `sku_id` diambil lewat `(array_agg(...))[1]`, bukan `min()`: Postgres tidak punya
     * agregat min/max untuk uuid, dan sku_id bukan bagian dimensi saldo sehingga baris mana
     * pun dalam grup bernilai sama.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """INSERT INTO inventory_balance_projection
                   (id, tenant_id, item_id, sku_id, location_id, custody_owner_id, custody_owner_kind, status, quantity, rebuilt_at)
                   SELECT gen_random_uuid(), l.tenant_id, l.item_id, (array_agg(l.sku_id))[1], l.location_id,
                          l.custody_owner_id, l.custody_owner_kind, l.status,
                          SUM(CASE WHEN l.direction = 'IN' THEN l.quantity ELSE -l.quantity END), :at
                   FROM inventory_movement_leg l
                   JOIN inventory_movement m ON m.id = l.movement_id AND m.tenant_id = l.tenant_id
                   WHERE l.tenant_id = :tenantId AND m.state = 'APPLIED'
                   GROUP BY l.tenant_id, l.item_id, l.location_id, l.custody_owner_id, l.custody_owner_kind, l.status
                   HAVING SUM(CASE WHEN l.direction = 'IN' THEN l.quantity ELSE -l.quantity END) <> 0""",
        nativeQuery = true,
    )
    fun rebuildForTenant(tenantId: UUID, at: Instant): Int
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
