package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.order.OperationCommand
import com.duluin.ftth.order.OrderView
import java.time.Instant
import java.util.UUID

/**
 * Penerimaan pesanan (P5.4) — SATU langkah yang mengerjakan tiga hal sekaligus:
 * pesanan berpindah ke `ACCEPTED`, calon pelanggannya dipromosikan jadi pelanggan, dan work
 * order PSB dibuka dengan taut balik ke pesanan ini.
 *
 * KENAPA satu transaksi, bukan tiga tombol berurutan: kalau operator harus menekan "terima",
 * lalu "promosikan", lalu "buat WO" satu per satu, maka setiap jeda di antaranya adalah keadaan
 * yang salah dan BISA bertahan selamanya — pelanggan yang sudah lahir tapi tak ada yang
 * memasangnya, atau pesanan diterima yang tak pernah menjadi pekerjaan siapa pun. Keduanya
 * hanya ketahuan saat pelanggan menelepon menanyakan kapan teknisi datang.
 */
interface OrderAcceptanceUseCase {
    fun accept(command: AcceptOrderCommand): OrderView
}

/**
 * [promotion] dipakai HANYA bila pesanan ini masih milik calon pelanggan. Pesanan dari
 * pelanggan lama melewatinya begitu saja.
 *
 * [appointmentStartsAt] menjadi jadwal awal work order-nya. Null = WO lahir tanpa jadwal dan
 * dispatcher yang menentukan — itu keadaan normal, bukan kesalahan.
 */
data class AcceptOrderCommand(
    val orderId: UUID,
    val expectedRevision: Long,
    val operation: OperationCommand,
    val promotion: PromoteOrderLeadCommand = PromoteOrderLeadCommand(),
    val appointmentStartsAt: Instant? = null,
    /** Roster teknisi awal untuk WO PSB-nya; kosong = belum ditugaskan. */
    val assignees: Set<UUID> = emptySet(),
)
