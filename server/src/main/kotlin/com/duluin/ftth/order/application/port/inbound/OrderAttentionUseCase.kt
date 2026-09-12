package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.order.OperationCommand
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import java.util.UUID

/**
 * Penanda portal (P4.7) — jalan bagi operator memberi tahu PELANGGAN bahwa pesanannya menunggu
 * sesuatu, tanpa memindahkan status pesanannya.
 *
 * KENAPA HANYA MANUAL, untuk sekarang. `PortalOrderStatus` sudah lama memuat `WAITING_CUSTOMER`
 * dan `REQUIRES_ATTENTION`, tapi TIDAK ADA satu pun jalur yang pernah menghasilkannya: keduanya
 * status mati. Aturan bisnis kapan sebuah pesanan "menunggu pelanggan" belum diputuskan pemilik
 * produk, dan mengarangnya di sini berarti pelanggan menerima pemberitahuan yang tak seorang pun
 * di perusahaan ini setuju untuk kirimkan. Yang dibangun karena itu hanya JALANNYA —
 * transisi, riwayat, dan tampilan — dengan pemicunya sengaja dikosongkan.
 *
 * Kandidat pemicu otomatis diusulkan terpisah untuk dipilih pemilik produk; begitu satu dipilih,
 * ia tinggal memanggil [flag] dari tempat kejadiannya.
 */
interface OrderAttentionUseCase {
    fun flag(command: FlagOrderCommand): OrderView
}

/** [flag] null = LEPAS penanda; pesanan kembali menampilkan status sesungguhnya. */
data class FlagOrderCommand(
    val orderId: UUID,
    val flag: OrderPortalFlag?,
    val reason: String?,
    val operation: OperationCommand,
)
