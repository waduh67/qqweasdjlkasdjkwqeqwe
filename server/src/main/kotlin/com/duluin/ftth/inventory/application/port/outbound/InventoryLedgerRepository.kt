package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.InventoryBalance
import com.duluin.ftth.inventory.domain.model.InventoryMovement
import com.duluin.ftth.inventory.domain.model.MovementLeg
import com.duluin.ftth.inventory.domain.model.MovementState
import java.time.Instant
import java.util.UUID

/**
 * Penyimpanan ledger mutasi stok beserta proyeksi saldonya.
 *
 * Tiga tabel di baliknya (`inventory_movement`, `inventory_movement_leg`,
 * `inventory_balance_projection`) sengaja disembunyikan di balik satu port: saldo harus
 * bergerak dalam transaksi yang SAMA dengan mutasi yang menyebabkannya, kalau tidak restart
 * di tengah jalan meninggalkan mutasi yang tercatat tapi tak pernah memotong stok.
 */
interface InventoryLedgerRepository {
    fun findByOperation(tenantId: UUID, namespace: String, operationKey: String): InventoryMovement?

    fun findById(movementId: UUID): InventoryMovement?

    fun findAll(tenantId: UUID): List<InventoryMovement>

    /**
     * Sisipkan mutasi baru. Mengembalikan `null` kalau sisipannya berhasil, atau mutasi yang
     * SUDAH tersimpan kalau identitas operasinya bentrok.
     *
     * Bentuk "kembalikan yang lama" dipilih, bukan melempar, karena pemenang lomba insert
     * bisa saja transaksi lain yang mengirim payload identik — itu replay yang sah, bukan
     * konflik. Pemanggil yang memutuskan lewat perbandingan payload hash.
     */
    fun appendIfAbsent(movement: InventoryMovement): InventoryMovement?

    fun updateState(movementId: UUID, state: MovementState): InventoryMovement

    /** Saldo yang bukan nol. Baris bernilai nol disembunyikan supaya sama dengan proyeksi lama. */
    fun balances(tenantId: UUID): List<InventoryBalance>

    /** Gerakkan proyeksi saldo sesuai leg yang baru berlaku. Dipanggil setelah mutasi APPLIED. */
    fun applyLegs(tenantId: UUID, legs: List<MovementLeg>, at: Instant)

    /** Hitung ulang seluruh proyeksi tenant dari leg mutasi APPLIED — jaring pengaman kalau proyeksi melenceng. */
    fun rebuildBalances(tenantId: UUID, at: Instant): List<InventoryBalance>
}
