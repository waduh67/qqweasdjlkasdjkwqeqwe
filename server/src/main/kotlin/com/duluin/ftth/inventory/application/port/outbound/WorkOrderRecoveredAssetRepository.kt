package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.WorkOrderRecoveredAsset
import java.util.UUID

/**
 * Penyimpanan aset yang DITARIK dari pelanggan saat work order DISMANTLE.
 *
 * Id barisnya dipakai sebagai `targetId` alokasi saga, yaitu bagian dari kunci idempotensi
 * `"${operationKey}:${targetId}"`. Karena itu [save] SELALU mempertahankan id yang diterima dan
 * TIDAK PERNAH membuat baris baru untuk koreksi — id yang berubah membuat saga memperlakukan
 * unit yang SUDAH ditambahkan ke saldo sebagai unit baru dan menambahkannya untuk kedua kalinya.
 */
interface WorkOrderRecoveredAssetRepository {
    /** Seluruh baris WO ini, termasuk yang sudah dibatalkan — layar teknisi perlu melihat jejaknya. */
    fun findByWorkOrder(tenantId: UUID, workOrderId: UUID): List<WorkOrderRecoveredAsset>

    fun findById(tenantId: UUID, id: UUID): WorkOrderRecoveredAsset?

    /**
     * Cari penarikan yang MASIH HIDUP atas satu unit fisik, di WO mana pun.
     *
     * Dipakai untuk menolak penarikan ganda dengan pesan yang bisa dibaca teknisi. Penjaga
     * terakhirnya tetap indeks parsial `work_order_recovered_asset_active_uq` — pencarian ini
     * bisa disalip request bersamaan, dan yang kalah memang harus gagal di basis data.
     */
    fun findActiveByAsset(tenantId: UUID, assetId: UUID): WorkOrderRecoveredAsset?

    fun save(row: WorkOrderRecoveredAsset): WorkOrderRecoveredAsset
}
