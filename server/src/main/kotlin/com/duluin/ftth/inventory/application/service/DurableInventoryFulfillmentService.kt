package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.inventory.InventoryFulfillmentResult
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryFulfillmentEffectJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryFulfillmentEffectJpaRepository
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementJpaRepository
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementLegJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.InventoryMovementLegJpaRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLedgerRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LegDirection
import com.duluin.ftth.inventory.domain.model.MovementKind
import com.duluin.ftth.inventory.domain.model.MovementLeg
import com.duluin.ftth.inventory.domain.model.MovementState
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DurableInventoryFulfillmentService(
    private val effects: InventoryFulfillmentEffectJpaRepository,
    private val movements: InventoryMovementJpaRepository,
    private val legs: InventoryMovementLegJpaRepository,
    /*
     * WAJIB, bukan opsional bernilai null.
     *
     * Bentuk opsional pernah dipertimbangkan demi unit test yang membangun service ini manual —
     * tapi tidak ada satu pun yang melakukannya, dan harganya terlalu mahal: `ledger?.applyLegs`
     * yang ter-skip DIAM-DIAM menghasilkan persis kerusakan yang paling dihindari di modul ini,
     * yaitu mutasi tercatat rapi sementara saldo tidak pernah bergerak, TANPA error di mana pun.
     * Kalau suatu saat bean-nya benar-benar tidak ada, yang benar adalah aplikasinya gagal
     * start — bukan berjalan sambil membocorkan stok.
     */
    private val ledger: InventoryLedgerRepository,
    private val assets: SerializedAssetRepository,
    private val locations: InventoryLocationRepository,
) {
    @Transactional
    fun apply(command: InventoryFulfillmentCommand, returned: Boolean): InventoryFulfillmentResult {
        // Dicek SEBELUM efek idempotency ditulis: kalau baris efek keburu tersimpan lalu leg-nya
        // ditolak CHECK `inventory_leg_serial_identity_ck`, percobaan ulang dengan data yang
        // benar akan dianggap replay dan mutasi stoknya TIDAK PERNAH terjadi.
        if (command.serialized && command.assetId == null) {
            throw ValidationException("Pemakaian barang berserial WAJIB menyebutkan aset (assetId)")
        }
        val prior = effects.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
        if (prior != null) {
            if (prior.payloadHash != command.payloadHash || prior.targetId != command.targetId) throw ConflictException("Operation key was used with a different payload")
            return InventoryFulfillmentResult(command.tenantId, command.operationKey, command.targetId, true, true, prior.recordedAt)
        }
        val now = Instant.now()
        val effectId = UUID.randomUUID()
        val inserted = effects.insertIfAbsent(effectId, command.tenantId, command.targetId, command.workOrderId, command.customerId, command.namespace, command.operationKey, command.payloadHash, command.itemCategory, command.quantity, command.installed, returned)
        if (inserted == 0) {
            val replay = effects.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
                ?: throw ConflictException("Inventory effect disappeared during concurrent insert")
            if (replay.payloadHash != command.payloadHash || replay.targetId != command.targetId) throw ConflictException("Operation key was used with a different payload")
            return InventoryFulfillmentResult(command.tenantId, command.operationKey, command.targetId, true, true, replay.recordedAt)
        }
        val movementId = UUID.randomUUID()
        val direction = if (returned) LegDirection.IN else LegDirection.OUT
        val status = if (returned) InventoryStatus.RETURNED else InventoryStatus.ISSUED
        movements.save(InventoryMovementJpaEntity(movementId, command.namespace, command.operationKey, command.payloadHash, command.actorId, command.reason, now, if (returned) MovementKind.RETURN else MovementKind.CONSUME, MovementState.APPLIED, null))
        legs.save(InventoryMovementLegJpaEntity(UUID.randomUUID(), movementId, direction, command.itemId, command.skuId, command.locationId, command.quantity, command.serialized, command.actorId, OwnerKind.TECHNICIAN, status, command.assetId, command.serialNumber))

        /*
         * Proyeksi saldo digerakkan di sini juga.
         *
         * Sebelumnya jalur ini HANYA menulis `inventory_movement` + `inventory_movement_leg`
         * dan melewati `inventory_balance_projection` sama sekali. Akibatnya persetujuan WO
         * menghasilkan ledger yang rapi sementara saldo van stock teknisi TIDAK PERNAH
         * berkurang: laporan mutasi bilang barang keluar, laporan saldo bilang barang masih
         * ada, dan tidak ada error di mana pun yang menunjukkan keduanya bertentangan.
         *
         * CATATAN: jalur ini TIDAK memvalidasi saldo negatif seperti
         * `InventoryMovementLedgerService.apply`. Yang menahannya adalah CHECK
         * `work_order_material_realized_ck` (realisasi <= yang dikeluarkan gudang) plus
         * mutasi ISSUE yang sudah tervalidasi saat barangnya keluar.
         */
        ledger.applyLegs(
            command.tenantId,
            listOf(
                MovementLeg(
                    direction, command.itemId, command.skuId, command.locationId, command.quantity,
                    command.serialized, command.actorId, OwnerKind.TECHNICIAN, status,
                    command.assetId, command.serialNumber,
                ),
            ),
            now,
        )
        if (!returned) consumeAsset(command.assetId)
        return InventoryFulfillmentResult(command.tenantId, command.operationKey, command.targetId, true, false, now)
    }

    /**
     * Tandai unit fisiknya CONSUMED.
     *
     * Tanpa ini aset tetap berstatus ISSUED selamanya walau saldonya sudah dipotong: unit
     * yang sudah terpasang di rumah pelanggan masih terlihat "ada di tas teknisi", ikut
     * terhitung saat opname van stock, dan bisa dipilih lagi sebagai barang yang mau diretur.
     *
     * Kegagalan di sini TIDAK ditelan — kalau transisinya ditolak, seluruh efek ini harus
     * gagal dan diulang, bukan meninggalkan saldo yang sudah bergerak dengan aset yang
     * statusnya tertinggal.
     */
    private fun consumeAsset(assetId: UUID?) {
        if (assetId == null) return
        // Aset dan lokasinya WAJIB ada, jadi ketidakhadirannya dilempar, bukan dilewati.
        // Alokasi berserial hanya lahir dari `work_order_material_serial`, yang `asset_id`-nya
        // ber-FK ke `inventory_serialized_asset` (V186) — kalau barisnya tetap tidak ketemu,
        // yang terjadi bukan "kebetulan kosong" melainkan RLS salah konteks atau data rusak.
        // Melewatinya diam-diam akan memotong saldo sambil meninggalkan unitnya ISSUED
        // selamanya: persis selisih yang baru ketahuan berbulan-bulan kemudian saat opname.
        val asset = assets.findById(assetId) ?: error("Aset $assetId tidak ditemukan saat mengonsumsi material")
        if (asset.status == InventoryStatus.CONSUMED) return
        val location = locations.findById(asset.locationId)
            ?: error("Lokasi ${asset.locationId} milik aset $assetId tidak ditemukan")
        assets.save(asset.transition(InventoryStatus.CONSUMED, location, asset.custody))
    }
}
