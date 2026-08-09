package com.duluin.ftth.customer

import java.util.UUID

/**
 * Kontak seorang pelanggan tersimpan (baru didaftarkan atau disunting).
 *
 * Diterbitkan tanpa memeriksa apakah nilainya benar-benar berubah — penerimanya menulis
 * ulang indeks secara idempoten, dan membandingkan nilai lama/baru di sini hanya akan
 * memindahkan logika itu ke tempat yang salah.
 *
 * Ada demi module `portal`: email & nomor HP adalah identitas yang dipakai pelanggan untuk
 * masuk tanpa menyebut ISP-nya. Operator yang mengoreksi nomor salah ketik harus membuat
 * nomor lama BERHENTI bisa dipakai masuk, dan itu hanya terjadi bila perubahan kontak
 * memancarkan sesuatu. Arah ketergantungannya tetap satu arah: customer tak perlu tahu
 * portal ada.
 *
 * [email] dan [phone] mentah apa adanya (belum ternormalkan) — bentuk kanoniknya urusan
 * penerima, karena aturannya milik portal, bukan customer.
 */
data class CustomerContactChanged(
    val tenantId: UUID,
    val customerId: UUID,
    val email: String?,
    val phone: String?,
)
