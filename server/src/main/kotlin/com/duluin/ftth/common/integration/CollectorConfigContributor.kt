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

    /**
     * Perintah BRAS yang menunggu dikirim ke collector ini (jalur turun, Phase 7c):
     * memutus sesi (Reset Login/isolir) atau CoA. Dipanggil dalam transaksi & konteks
     * tenant denyut yang sama; implementasi boleh menandai perintahnya "terkirim" saat
     * menyerahkannya di sini. Default kosong agar contributor non-bng tak perlu tahu-menahu.
     */
    fun pendingBngActionsFor(collectorId: UUID, tenantId: UUID): List<BngActionDispatch> = emptyList()
}

/**
 * Target BRAS yang disumbangkan sebuah module ke kanal collector. Tipe netral di
 * shared kernel: monitoring memetakannya ke DTO wire (`contract.NasTarget`) sehingga
 * module penyumbang tak perlu tahu format protokol collector.
 *
 * [expectedUsernames] = akun PPPoE aktif di BRAS ini; hanya adapter simulator yang
 * memakainya (memerankan sesi yang cocok pelanggan nyata), adapter sungguhan
 * mengabaikannya.
 *
 * Kredensial kontrol ([apiUsername]..[coaSecret]) sudah TERDEKRIPSI di sini — module
 * penyumbang membacanya dari domainnya, monitoring meneruskannya apa adanya ke DTO wire
 * (aman: kanal collector TLS, cermin community SNMP OLT). Kosong untuk BRAS tanpa adapter
 * nyata yang dikonfigurasi.
 */
data class NasPollTarget(
    val nasId: UUID,
    val name: String,
    val vendor: String,
    val host: String?,
    val adapterType: String,
    val expectedUsernames: List<String>,
    val apiUsername: String? = null,
    val apiSecret: String? = null,
    val apiPort: Int? = null,
    val apiUseTls: Boolean = true,
    val apiDatabase: String? = null,
    val coaSecret: String? = null,
)

/**
 * Perintah BRAS yang disumbangkan module `bng` ke kanal collector — tipe netral shared
 * kernel, monitoring memetakannya ke DTO wire (`contract.BngActionCommand`) tanpa perlu
 * tahu detail protokol. [kind] memakai string netral (`DISCONNECT`/`COA`) agar `common`
 * tak bergantung pada enum `contract`. [downMbps]/[upMbps] hanya untuk CoA.
 */
data class BngActionDispatch(
    val actionId: UUID,
    val nasId: UUID,
    val kind: String,
    val username: String,
    val downMbps: Int?,
    val upMbps: Int?,
)
