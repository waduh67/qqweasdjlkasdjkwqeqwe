package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.inventory.application.port.outbound.InventoryLedgerRepository
import com.duluin.ftth.inventory.domain.model.InventoryBalance
import com.duluin.ftth.inventory.domain.model.InventoryMovement
import com.duluin.ftth.inventory.domain.model.LegDirection
import com.duluin.ftth.inventory.domain.model.MovementLeg
import com.duluin.ftth.inventory.domain.model.MovementState
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Menyimpan ledger mutasi stok ke `inventory_movement` + `inventory_movement_leg` dan
 * memelihara `inventory_balance_projection` secara inkremental.
 *
 * Proyeksi dipelihara inkremental (bukan dihitung ulang tiap baca) karena jalur panasnya
 * adalah "cek stok cukup atau tidak" pada setiap pengeluaran barang: menghitung ulang seluruh
 * riwayat mutasi tenant di setiap permintaan akan melambat linier seumur hidup sistem.
 * Rekonsiliasi penuh tersedia lewat [rebuildBalances] sebagai jaring pengaman.
 */
@Component
class InventoryLedgerPersistenceAdapter(
    private val movements: InventoryMovementJpaRepository,
    private val legs: InventoryMovementLegJpaRepository,
    private val projections: InventoryBalanceProjectionJpaRepository,
) : InventoryLedgerRepository {

    override fun findByOperation(tenantId: UUID, namespace: String, operationKey: String): InventoryMovement? =
        movements.findByTenantIdAndOperationNamespaceAndOperationKey(tenantId, namespace, operationKey)?.toDomain()

    override fun findById(movementId: UUID): InventoryMovement? =
        movements.findById(movementId).orElse(null)?.toDomain()

    override fun findAll(tenantId: UUID): List<InventoryMovement> {
        val rows = movements.findAllForTenant(tenantId)
        if (rows.isEmpty()) return emptyList()
        // Satu query leg untuk semua mutasi: jalur ini dipakai layar riwayat, dan versi
        // per-baris akan melahirkan N+1 query yang persis sebesar panjang riwayat gudang.
        val legsByMovement = legs.findAllForMovements(tenantId, rows.map { it.id }).groupBy { it.movementId }
        return rows.map { it.toDomain(legsByMovement[it.id].orEmpty()) }
    }

    override fun appendIfAbsent(movement: InventoryMovement): InventoryMovement? {
        val inserted = movements.insertIfAbsent(
            movement.movementId, movement.tenantId, movement.namespace, movement.operationKey,
            movement.payloadHash, movement.actorId, movement.reason, movement.serverReceivedAt,
            movement.kind.name, movement.state.name, movement.compensatesMovementId,
        )
        if (inserted == 0) {
            // ON CONFLICT DO NOTHING menunggu transaksi lawan selesai dulu, jadi pada titik ini
            // baris pemenang sudah commit dan pasti terbaca.
            return movements.findByTenantIdAndOperationNamespaceAndOperationKey(movement.tenantId, movement.namespace, movement.operationKey)?.toDomain()
                ?: throw NotFoundException("Mutasi stok hilang saat sisipan bersamaan")
        }
        movement.legs.forEach { leg ->
            legs.save(
                InventoryMovementLegJpaEntity(
                    UuidV7.generate(), movement.movementId, leg.direction, leg.itemId, leg.skuId, leg.locationId,
                    leg.quantity, leg.serialized, leg.custodyOwnerId, leg.custodyOwnerKind, leg.status,
                    leg.assetId, leg.serialNumber,
                ),
            )
        }
        return null
    }

    override fun updateState(movementId: UUID, state: MovementState): InventoryMovement {
        val entity = movements.findById(movementId).orElse(null) ?: throw NotFoundException("Mutasi stok tidak ditemukan")
        entity.state = state
        return movements.save(entity).toDomain()
    }

    override fun balances(tenantId: UUID): List<InventoryBalance> =
        projections.findNonZeroForTenant(tenantId).map {
            InventoryBalance(it.tenantId!!, it.itemId, it.skuId, it.locationId, it.custodyOwnerId, it.custodyOwnerKind, it.status, it.quantity)
        }

    /**
     * Dua langkah per leg — sisipkan dimensi bersaldo 0 dulu, baru gerakkan deltanya.
     *
     * Bukan satu upsert: lihat [InventoryBalanceProjectionJpaRepository.ensureDimension].
     * Postgres menguji CHECK `quantity >= 0` pada baris calon insert SEBELUM arbitrase
     * ON CONFLICT, jadi upsert dengan delta negatif selalu ditolak dan barang keluar mustahil
     * tercatat.
     */
    override fun applyLegs(tenantId: UUID, legs: List<MovementLeg>, at: Instant) {
        legs.forEach { leg ->
            val delta = if (leg.direction == LegDirection.IN) leg.quantity else -leg.quantity
            projections.ensureDimension(
                UuidV7.generate(), tenantId, leg.itemId, leg.skuId, leg.locationId,
                leg.custodyOwnerId, leg.custodyOwnerKind.name, leg.status.name, at,
            )
            projections.applyDelta(
                tenantId, leg.itemId, leg.locationId,
                leg.custodyOwnerId, leg.custodyOwnerKind.name, leg.status.name, delta, at,
            )
        }
    }

    override fun rebuildBalances(tenantId: UUID, at: Instant): List<InventoryBalance> {
        projections.deleteAllForTenant(tenantId)
        projections.rebuildForTenant(tenantId, at)
        return balances(tenantId)
    }

    private fun InventoryMovementJpaEntity.toDomain(): InventoryMovement =
        toDomain(legs.findAllByTenantIdAndMovementIdOrderByIdAsc(tenantId!!, id))

    private fun InventoryMovementJpaEntity.toDomain(rows: List<InventoryMovementLegJpaEntity>) = InventoryMovement(
        id, tenantId!!, operationNamespace, operationKey, payloadHash, actorId, reason, serverReceivedAt,
        kind, rows.map { it.toDomain() }, state, compensatesMovementId,
    )

    private fun InventoryMovementLegJpaEntity.toDomain() = MovementLeg(
        direction, itemId, skuId, locationId, quantity, serialized, custodyOwnerId, custodyOwnerKind, status,
        assetId, serialNumber,
    )
}
