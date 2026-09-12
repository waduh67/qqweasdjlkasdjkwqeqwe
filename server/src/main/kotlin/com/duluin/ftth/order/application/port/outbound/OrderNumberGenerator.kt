package com.duluin.ftth.order.application.port.outbound

import java.time.Instant
import java.util.UUID

/**
 * Pembuat nomor pesanan `ORD-YYMM-NNNN`, berurut per (tenant, periode).
 *
 * ATURAN: implementasi WAJIB aman dari balapan. Dua permintaan bersamaan tidak boleh
 * menerima nomor yang sama — kalau terjadi, UNIQUE (tenant_id, order_number) menolak salah
 * satunya dan pesanan pelanggan hilang tanpa pernah tercatat. Karena itu `SELECT max()+1`
 * DILARANG: dua transaksi membaca max yang sama sebelum salah satunya menulis.
 */
interface OrderNumberGenerator {
    fun next(tenantId: UUID, at: Instant): String

    fun next(tenantId: UUID): String = next(tenantId, Instant.now())
}
