package com.duluin.ftth.network

import java.util.UUID

/**
 * Ditanyai module network sebelum sebuah ODP dihapus: "apakah masih ada
 * sesuatu milikmu yang menempel di sini?"
 *
 * Arah dependensinya sengaja dibalik. Module network tidak boleh bergantung pada
 * customer (customer sudah bergantung pada network, dan itu akan jadi siklus),
 * jadi network yang MENDEKLARASIKAN kontrak ini dan module lain yang mengisinya.
 * Spring mengumpulkan seluruh implementasi menjadi satu daftar.
 *
 * Tanpa ini, menghapus ODP berisi pelanggan akan berhasil diam-diam dan
 * menyisakan ONU menggantung — pelanggan tetap tersambung secara fisik tapi
 * hilang dari peta, dan tidak ada yang tahu sampai ada yang komplain.
 */
interface OdpUsageProbe {

    /** Berapa banyak entitas milik module ini yang masih menempel pada ODP tersebut. */
    fun countAttachedTo(odpId: UUID): Long

    /** Sebutan untuk pesan galat, mis. "ONU pelanggan". */
    fun describeUsage(): String
}
