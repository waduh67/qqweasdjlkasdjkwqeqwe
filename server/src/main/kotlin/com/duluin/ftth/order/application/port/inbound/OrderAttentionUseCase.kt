package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.order.OperationCommand
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import java.util.UUID

/**
 * Penanda portal (P4.7) — jalan bagi operator memberi tahu PELANGGAN bahwa pesanannya menunggu
 * sesuatu, tanpa memindahkan status pesanannya.
 *
 * Ini jalur MANUSIA. Penanda yang dipasang lewat sini bersumber `OPERATOR`, dan otomasi apa pun
 * TIDAK PERNAH melepasnya — operator yang menekan tombolnya baru saja menelepon pelanggannya dan
 * tahu sesuatu yang tidak diketahui sistem. Jalur otomatis punya pintunya sendiri, lihat
 * [OrderPortalAutomationUseCase].
 */
interface OrderAttentionUseCase {
    fun flag(command: FlagOrderCommand): OrderView

    /**
     * "Pelanggan tidak bisa dihubungi" — operator yang MEMUTUSKAN, sistem yang MENULIS kalimatnya.
     *
     * Kalimatnya tidak boleh diketik operator. Catatan yang benar secara internal ("3x ditelpon,
     * nomornya sibuk terus, kayaknya nomor kantor") adalah kalimat yang tak boleh dibaca
     * pelanggan yang bersangkutan, dan setiap operator akan menulisnya dengan nada berbeda.
     * Sistem memakai satu kalimat baku: lihat `OrderPortalNarrative.CUSTOMER_UNREACHABLE`.
     */
    fun markUnreachable(command: MarkUnreachableCommand): OrderView
}

/** [flag] null = LEPAS penanda; pesanan kembali menampilkan status sesungguhnya. */
data class FlagOrderCommand(
    val orderId: UUID,
    val flag: OrderPortalFlag?,
    val reason: String?,
    val operation: OperationCommand,
)

/**
 * [note] adalah catatan INTERNAL operator (opsional) — masuk ke riwayat pesanan, TIDAK pernah ke
 * halaman lacak pelanggan.
 */
data class MarkUnreachableCommand(
    val orderId: UUID,
    val note: String?,
    val operation: OperationCommand,
)
