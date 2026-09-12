package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.port.outbound.InventoryLedgerRepository
import com.duluin.ftth.inventory.application.port.outbound.MovementFilter
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Buku besar mutasi stok — satu-satunya jalan masuk perubahan saldo gudang.
 *
 * Dulu seluruh state kelas ini hidup di `mutableListOf` dalam memori proses: mutasi hilang
 * setiap restart dan sama sekali tidak tersentuh Row-Level Security, sehingga "gudang" hanya
 * nyata selama JVM-nya hidup. Sekarang semuanya lewat [InventoryLedgerRepository].
 *
 * Kunci idempotency-nya `(tenantId, namespace, operationKey)`; kunci yang sama dengan payload
 * hash berbeda adalah KONFLIK, bukan replay — kalau dilonggarkan, retry klien yang membawa
 * jumlah berbeda akan diterima diam-diam sebagai "sudah pernah" dan stok tidak pernah bergerak.
 */
@Service
class InventoryMovementLedgerService(
    private val ledger: InventoryLedgerRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun apply(command: MovementCommand): InventoryMovement {
        val prior = ledger.findByOperation(command.tenantId, command.namespace, command.operationKey)
        if (prior != null) return replayOrConflict(prior, command)

        require(command.legs.all { it.quantity > 0 }) { "movement quantity must be positive" }
        // Sumber kebenarannya ada di enum-nya sendiri (`MovementKind.requiresApproval`), bukan
        // daftar terpisah di sini: daftar yang terpisah pasti ketinggalan saat jenis mutasi baru
        // lahir, dan jenis baru yang kelewat akan LANGSUNG BERLAKU tanpa pernah minta persetujuan.
        val state = if (command.kind.requiresApproval) MovementState.PENDING_APPROVAL else MovementState.APPLIED
        val movement = InventoryMovement(
            UuidV7.generate(), command.tenantId, command.namespace, command.operationKey, command.payloadHash,
            command.actorId, command.reason, Instant.now(clock), command.kind, command.legs, state,
            command.compensatesMovementId,
        )
        // Saldo divalidasi SEBELUM insert supaya mutasi yang akan membuat stok minus tidak
        // pernah punya jejak di tabel — ledger yang berisi baris "gagal" membuat rebuild
        // proyeksi harus tahu baris mana yang boleh dihitung.
        if (state == MovementState.APPLIED) validateProjection(movement)

        // Dua request dengan kunci sama bisa lolos pemeriksaan di atas bersamaan (masing-masing
        // di transaksi sendiri sehingga tak melihat baris lawan yang belum commit). Penjaga
        // sebenarnya adalah UNIQUE (tenant_id, operation_namespace, operation_key) di DB:
        // yang kalah menerima baris pemenang dan memperlakukannya sebagai replay.
        val raced = ledger.appendIfAbsent(movement)
        if (raced != null) return replayOrConflict(raced, command)

        if (state == MovementState.APPLIED) ledger.applyLegs(command.tenantId, command.legs, movement.serverReceivedAt)
        return movement
    }

    @Transactional
    fun reverse(originalMovementId: UUID, command: MovementCommand): InventoryMovement {
        val original = ledger.findById(originalMovementId) ?: throw NotFoundException("Mutasi stok tidak ditemukan")
        if (original.state != MovementState.APPLIED) throw ConflictException("Hanya mutasi yang sudah berlaku yang bisa dibalik")
        val reversed = command.copy(
            kind = MovementKind.REVERSAL,
            legs = original.legs.map { it.copy(direction = if (it.direction == LegDirection.IN) LegDirection.OUT else LegDirection.IN) },
            compensatesMovementId = originalMovementId,
        )
        return apply(reversed)
    }

    @Transactional
    fun approvePending(movementId: UUID): InventoryMovement {
        val pending = ledger.findById(movementId) ?: throw NotFoundException("Mutasi stok tidak ditemukan")
        if (pending.state != MovementState.PENDING_APPROVAL) throw ConflictException("Mutasi stok tidak sedang menunggu persetujuan")
        validateProjection(pending)
        val applied = ledger.updateState(movementId, MovementState.APPLIED)
        // Saldo baru bergerak di sini, bukan saat mutasi diajukan: mutasi yang masih
        // PENDING_APPROVAL belum boleh memotong stok, kalau tidak penolakan approval
        // meninggalkan stok yang sudah terlanjur berkurang.
        ledger.applyLegs(applied.tenantId, applied.legs, Instant.now(clock))
        return applied
    }

    @Transactional
    fun rejectPending(movementId: UUID): InventoryMovement {
        val pending = ledger.findById(movementId) ?: throw NotFoundException("Mutasi stok tidak ditemukan")
        if (pending.state != MovementState.PENDING_APPROVAL) throw ConflictException("Mutasi stok tidak sedang menunggu persetujuan")
        return ledger.updateState(movementId, MovementState.FAILED_PERMANENT)
    }

    @Transactional(readOnly = true)
    fun movements(tenantId: UUID): List<InventoryMovement> = ledger.findAll(tenantId)

    /**
     * Riwayat ber-halaman untuk layar dan laporan. [movements] TIDAK boleh dipakai di sana:
     * ledger gudang tidak pernah dipangkas, jadi memuat seluruh riwayat tenant hanya untuk
     * menampilkan 20 baris akan menghabiskan heap server begitu mutasinya puluhan ribu.
     */
    @Transactional(readOnly = true)
    fun movementPage(tenantId: UUID, filter: MovementFilter, page: PageRequest): Page<InventoryMovement> =
        ledger.findPage(tenantId, filter, page)

    /** Cari mutasi lewat identitas operasinya — dipakai pemanggil yang perlu mendeteksi replay-nya sendiri. */
    @Transactional(readOnly = true)
    fun movementByOperation(tenantId: UUID, namespace: String, operationKey: String): InventoryMovement? =
        ledger.findByOperation(tenantId, namespace, operationKey)

    @Transactional(readOnly = true)
    fun movement(movementId: UUID): InventoryMovement? = ledger.findById(movementId)

    @Transactional(readOnly = true)
    fun balances(tenantId: UUID): List<InventoryBalance> = ledger.balances(tenantId)

    /**
     * Hitung ulang proyeksi saldo dari leg mutasi APPLIED.
     *
     * BUKAN readOnly: ia menulis ulang `inventory_balance_projection`. Dipakai sebagai jaring
     * pengaman kalau proyeksi inkremental pernah melenceng (mis. akibat perbaikan data manual).
     */
    @Transactional
    fun rebuild(tenantId: UUID): List<InventoryBalance> = ledger.rebuildBalances(tenantId, Instant.now(clock))

    /**
     * Tolak mutasi yang akan membuat salah satu dimensi saldo jadi negatif.
     *
     * Saldo negatif berarti gudang mengaku mengeluarkan barang yang tak pernah dimilikinya —
     * begitu itu terjadi, tidak ada cara membedakan salah input dari barang yang benar-benar
     * hilang, dan laporan selisih kehilangan artinya.
     */
    private fun validateProjection(candidate: InventoryMovement) {
        val current = ledger.balances(candidate.tenantId)
            .associateBy({ BalanceKey(it.itemId, it.locationId, it.custodyOwnerId, it.custodyOwnerKind, it.status) }, InventoryBalance::quantity)
            .toMutableMap()
        candidate.legs.forEach { leg ->
            val key = BalanceKey(leg.itemId, leg.locationId, leg.custodyOwnerId, leg.custodyOwnerKind, leg.status)
            val next = (current[key] ?: 0) + if (leg.direction == LegDirection.IN) leg.quantity else -leg.quantity
            if (next < 0) throw InventoryInsufficientBalance("movement would create a negative balance")
            current[key] = next
        }
    }

    private fun replayOrConflict(stored: InventoryMovement, command: MovementCommand): InventoryMovement {
        if (stored.payloadHash != command.payloadHash) throw ConflictException("operation key was used with a different payload")
        return stored
    }

    /**
     * Dimensi saldo mengikuti UNIQUE `inventory_balance_dimension_uq` — tanpa `skuId`.
     * Kalau `skuId` ikut jadi kunci, satu dimensi bisa punya dua baris di memori tapi hanya
     * satu baris di tabel, dan proyeksi jadi tidak pernah cocok dengan validasinya sendiri.
     */
    private data class BalanceKey(
        val itemId: UUID,
        val locationId: UUID,
        val ownerId: UUID,
        val ownerKind: OwnerKind,
        val status: InventoryStatus,
    )

}
