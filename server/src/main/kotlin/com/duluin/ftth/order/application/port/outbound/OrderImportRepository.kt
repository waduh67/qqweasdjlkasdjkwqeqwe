package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.order.domain.model.OrderImportBatch
import com.duluin.ftth.order.domain.model.OrderImportRow
import java.util.UUID

/**
 * Penyimpanan berkas impor dan baris-barisnya.
 *
 * Seluruh pencarian di sini bersandar pada tenant aktif (`@TenantId` + RLS). [tenantId] tetap
 * diminta eksplisit di [findBatchByContentHash] karena ia dipakai di jalur yang tenant-nya sudah
 * dipegang pemanggil, dan membacanya dua kali dari dua sumber adalah cara paling mudah membuat
 * keduanya menyimpang.
 */
interface OrderImportRepository {
    fun saveBatch(batch: OrderImportBatch)
    fun findBatch(id: UUID): OrderImportBatch?

    /** Janji idempotensi tingkat BERKAS: unggahan ulang byte yang sama memulangkan batch ini. */
    fun findBatchByContentHash(tenantId: UUID, contentHash: String): OrderImportBatch?

    /** Riwayat impor, terbaru lebih dulu. */
    fun recentBatches(limit: Int): List<OrderImportBatch>

    fun saveRows(rows: List<OrderImportRow>)
    fun saveRow(row: OrderImportRow)
    fun findRows(batchId: UUID): List<OrderImportRow>
    fun findRow(id: UUID): OrderImportRow?
}
