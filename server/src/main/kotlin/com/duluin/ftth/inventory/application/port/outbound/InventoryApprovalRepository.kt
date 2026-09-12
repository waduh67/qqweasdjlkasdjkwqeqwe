package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.ApproverDelegation
import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecisionSnapshot
import com.duluin.ftth.inventory.domain.model.InventoryApprovalEffect
import com.duluin.ftth.inventory.domain.model.InventoryApprovalRequest
import java.util.UUID

/**
 * Penyimpanan matriks persetujuan gudang (`inventory_approval`, `..._decision`,
 * `..._effect`, `..._delegation`).
 *
 * Keputusan disimpan sebagai baris terpisah dan TIDAK PERNAH diperbarui — tabelnya dijaga
 * trigger `inventory_approval_decision_immutable`. Riwayat siapa menyetujui apa adalah
 * bukti audit; kalau ia bisa ditimpa, seluruh kontrol empat-mata jadi tak ada artinya.
 */
interface InventoryApprovalRepository {
    fun findById(approvalId: UUID): InventoryApprovalRequest?

    fun findByOperation(tenantId: UUID, operationKey: String): InventoryApprovalRequest?

    fun findPending(tenantId: UUID): List<InventoryApprovalRequest>

    /**
     * Sisipkan permintaan baru; kembalikan permintaan yang SUDAH ada kalau
     * `(tenantId, operationKey)` bentrok. Sama alasannya dengan ledger: pemenang lomba
     * insert bisa jadi replay yang sah.
     */
    fun appendIfAbsent(request: InventoryApprovalRequest): InventoryApprovalRequest?

    fun updateStatus(approvalId: UUID, request: InventoryApprovalRequest)

    /**
     * Tandai permintaan sebagai kedaluwarsa DI LUAR transaksi pemanggil.
     *
     * Pemanggilnya menolak keputusan yang datang terlambat dengan lemparan; kalau penandaan
     * ini ikut dalam transaksi yang sama, ia ikut di-rollback dan permintaan yang sudah lewat
     * tenggat akan terus tampil PENDING selamanya.
     */
    fun markExpired(approvalId: UUID, revision: Long)

    fun appendDecision(approvalId: UUID, snapshot: InventoryApprovalDecisionSnapshot)

    fun delegations(tenantId: UUID): List<ApproverDelegation>

    fun saveDelegation(delegation: ApproverDelegation)

    /** `true` kalau efek ini baru dicatat; `false` kalau approval tersebut sudah pernah memancarkan efek. */
    fun recordEffect(effect: InventoryApprovalEffect): Boolean

    fun effects(tenantId: UUID): List<InventoryApprovalEffect>
}
