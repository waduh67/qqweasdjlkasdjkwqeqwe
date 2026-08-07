package com.duluin.ftth.customer

import java.util.UUID

/**
 * Peristiwa daur hidup ONU pelanggan yang diterbitkan module customer setelah perubahan
 * berhasil.
 *
 * Diletakkan di base package (permukaan publik customer) supaya module lain — khususnya
 * `monitoring`, yang merawat kotak masuk provisioning — bisa bereaksi tanpa bergantung
 * pada internal customer, dan tanpa menimbulkan ketergantungan balik (customer tak perlu
 * tahu monitoring ada). Penerbitan in-JVM; konsumen mendengarkan pada fase AFTER_COMMIT
 * agar hanya melihat perubahan yang benar-benar ter-commit.
 */

/**
 * Sebuah ONU baru didaftarkan untuk pelanggan — mis. operator mencoloknya manual dari
 * halaman pelanggan, di LUAR kotak masuk provisioning. Module monitoring mendengarkan
 * untuk menuntaskan sendiri baris "Menunggu" berserial sama (bila ada) secara SINKRON,
 * bukan menunggu siklus polling berikutnya menyadari serialnya kini dikenal.
 *
 * [serialNumber] sebagaimana tersimpan agregat ONU (sudah dinormalkan uppercase);
 * konsumen tetap menormalkannya sendiri saat mencocokkan agar tak bergantung pada itu.
 */
data class OnuRegistered(
    val tenantId: UUID,
    val onuId: UUID,
    val customerId: UUID,
    val serialNumber: String,
)
