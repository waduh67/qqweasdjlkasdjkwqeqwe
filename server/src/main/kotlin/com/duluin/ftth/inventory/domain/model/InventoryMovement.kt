package com.duluin.ftth.inventory.domain.model

import java.time.Instant
import java.util.UUID

enum class MovementKind {
    RESTOCK, RECEIVE, RESERVE, RELEASE, ISSUE, ISSUE_EXCEPTION, TRANSFER, TRANSFER_RECEIPT, RETURN,
    REPAIR, QUARANTINE, ADJUSTMENT, LOSS, SCRAP, WRITE_OFF, COUNT_VARIANCE, DISPOSAL, CONSUME, REVERSAL,
}

enum class LegDirection { IN, OUT }
enum class MovementState { APPLIED, PENDING_APPROVAL, FAILED_PERMANENT, REQUIRES_MANUAL_REPAIR }

/**
 * Satu sisi mutasi stok.
 *
 * [assetId] dan [serialNumber] SENGAJA ditambahkan (V174) supaya mutasi kuantitas dan
 * mutasi aset serial berhenti jadi dua pulau terpisah: tanpa keduanya, saldo bisa bilang
 * "3 ONT di gudang" sementara daftar aset bilang 2 dan tidak ada baris mana pun yang bisa
 * menunjukkan unit mana yang hilang.
 */
data class MovementLeg(
    val direction: LegDirection,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val assetId: UUID? = null,
    /**
     * Disalin dari aset saat mutasi terjadi, bukan di-join belakangan: ledger adalah catatan
     * historis dan nomor seri yang tercatat tidak boleh ikut berubah kalau baris asetnya
     * kelak diperbaiki.
     */
    val serialNumber: String? = null,
) {
    init {
        require(quantity > 0) { "movement leg quantity must be positive" }
        require(!serialized || quantity == 1) { "serialized movement quantity must be one" }
        // Cermin dari CHECK `inventory_leg_serial_identity_ck`. Divalidasi di domain juga
        // supaya kegagalan muncul sebagai invariant yang terbaca, bukan error SQL mentah
        // jauh di dalam flush Hibernate.
        require(!serialized || assetId != null) { "mutasi barang berserial WAJIB menyebut aset (assetId)" }
        require(assetId != null || serialNumber == null) { "nomor seri tanpa aset tidak bisa direkonsiliasi" }
    }
}

data class InventoryMovement(
    val movementId: UUID,
    val tenantId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val actorId: UUID,
    val reason: String,
    val serverReceivedAt: Instant,
    val kind: MovementKind,
    val legs: List<MovementLeg>,
    val state: MovementState,
    val compensatesMovementId: UUID? = null,
) {
    init {
        require(namespace.isNotBlank() && operationKey.isNotBlank()) { "operation identity is required" }
        require(payloadHash.isNotBlank()) { "payload hash is required" }
        require(reason.isNotBlank()) { "movement reason is required" }
        require(legs.isNotEmpty()) { "movement must have legs" }
        require(legs.count { it.direction == LegDirection.IN } == legs.count { it.direction == LegDirection.OUT } || kind in setOf(MovementKind.RECEIVE, MovementKind.RETURN, MovementKind.REPAIR, MovementKind.QUARANTINE, MovementKind.DISPOSAL, MovementKind.CONSUME, MovementKind.ADJUSTMENT, MovementKind.COUNT_VARIANCE)) {
            "paired movement must have balanced IN and OUT legs"
        }
    }
}

data class InventoryBalance(
    val tenantId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val quantity: Int,
)

data class MovementCommand(
    val tenantId: UUID,
    val actorId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val reason: String,
    val kind: MovementKind,
    val legs: List<MovementLeg>,
    val compensatesMovementId: UUID? = null,
)

class InventoryMovementConflict(message: String) : IllegalArgumentException(message)
class InventoryInsufficientBalance(message: String) : IllegalStateException(message)
