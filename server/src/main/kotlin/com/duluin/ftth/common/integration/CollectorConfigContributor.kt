package com.duluin.ftth.common.integration

import java.util.UUID

/**
 * Titik ekstensi bagi module lain untuk menyumbang target polling ke konfigurasi
 * yang dikembalikan server pada tiap denyut collector.
 *
 * Kanal collector (module `monitoring`) semula hanya mengenal OLT. Agar module
 * `bng` (BRAS/RADIUS) bisa ikut menitipkan BRAS yang harus di-polling TANPA
 * monitoring mengimpor bng — yang menjadikan `monitoring↔bng` sebuah siklus module
 * — monitoring memanggil seluruh contributor lewat seam di shared kernel ini.
 * Hasilnya: `monitoring→common`, `bng→common`, nol siklus. Sama polanya dengan
 * [OpticalDegradationDetected] yang menaruh event di sini demi memutus siklus.
 *
 * Dipanggil di dalam transaksi denyut & konteks tenant yang sudah terpasang;
 * implementasi mengembalikan target ter-scope tenant aktif. Kegagalan satu
 * contributor tidak boleh menjatuhkan polling OLT — pemanggil membungkus tiap
 * panggilan.
 */
interface CollectorConfigContributor {
    fun nasTargetsFor(collectorId: UUID, tenantId: UUID): List<NasPollTarget>
}

/**
 * Target BRAS yang disumbangkan sebuah module ke kanal collector. Tipe netral di
 * shared kernel: monitoring memetakannya ke DTO wire (`contract.NasTarget`) sehingga
 * module penyumbang tak perlu tahu format protokol collector.
 *
 * [expectedUsernames] = akun PPPoE aktif di BRAS ini; hanya adapter simulator yang
 * memakainya (memerankan sesi yang cocok pelanggan nyata), adapter sungguhan
 * mengabaikannya.
 */
data class NasPollTarget(
    val nasId: UUID,
    val name: String,
    val vendor: String,
    val host: String?,
    val adapterType: String,
    val expectedUsernames: List<String>,
)
