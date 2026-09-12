package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.order.CreateOrderCommand
import com.duluin.ftth.order.OrderTransitionCommand
import com.duluin.ftth.order.OrderView
import java.util.UUID

/**
 * Jalur pesanan untuk pemanggil yang TIDAK punya principal.
 *
 * `OrderApi` mengambil tenant & pelaku dari [com.duluin.ftth.common.security.CurrentUserProvider],
 * yang pada permintaan anonim melempar `IllegalStateException` mentah — 500 tanpa penjelasan,
 * bukan pesan yang bisa dibaca pengunjung. Pintu pemesanan publik (P4.4) memang tak punya JWT:
 * `SecurityConfig` mematikan authentication anonim, jadi `SecurityContextHolder` benar-benar
 * kosong di sana.
 *
 * Karena itu tenant dan pelaku di sini EKSPLISIT. [actorId] `null` berarti "bukan siapa-siapa
 * dari dalam" — riwayat pesanan akan menampilkan kejadian tanpa pelaku, yang jujur: memang tak
 * ada operator yang menekan tombolnya.
 *
 * ATURAN: [tenantId] WAJIB sudah terpasang di `TenantContext` oleh pemanggil sebelum masuk ke
 * sini. Tanpa GUC `app.tenant_id`, query mengembalikan NOL BARIS tanpa error apa pun dan
 * pesanan pengunjung akan gagal dengan pesan yang menyesatkan.
 */
interface SystemOrderUseCase {
    fun create(tenantId: UUID, actorId: UUID?, command: CreateOrderCommand): OrderView
    fun transition(tenantId: UUID, actorId: UUID?, command: OrderTransitionCommand): OrderView
}
