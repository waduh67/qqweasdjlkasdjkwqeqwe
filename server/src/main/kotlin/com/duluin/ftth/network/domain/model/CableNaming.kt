package com.duluin.ftth.network.domain.model

/**
 * Perakit kode kabel otomatis.
 *
 * Kode aset itu barang ucap: ia disebut lewat radio saat gangguan ("cek DIST-ODC-JKT-001-JB-001"),
 * ditulis tangan di label selubung, dan dicocokkan orang di lapangan dengan yang tertera di layar.
 * Maka kode yang dibuat sistem harus bisa DIBACA — bukan pengenal internal yang kebetulan unik.
 *
 * Bentuknya JENIS-UJUNG-UJUNG, karena itulah cara orang menyebut sebuah ruas: bukan "kabel nomor
 * sekian", melainkan "distribusi dari kabinet itu ke kotak sana". Kedua ujungnya sudah punya kode
 * yang dikenal ([Odc.code], [JointBox.code], dst.), jadi kode kabel cukup meminjamnya alih-alih
 * memperkenalkan penomoran baru yang harus dihafal terpisah.
 *
 * Yang dirakit di sini cuma USULAN bentuk. Ketunggalannya diurus pemanggil (dua selubung antara
 * sepasang kotak yang sama itu lumrah — rute utara & rute selatan), dan operator selalu boleh
 * menimpanya dengan kode penomoran perusahaannya sendiri.
 */
internal object CableNaming {

    /** Sama dengan batas pola kode di [AssetNaming] — yang lebih panjang ditolak domain. */
    private const val MAX_LENGTH = 40

    /**
     * Singkatan jenis, sependek mungkin tapi masih terbaca sekali lihat. Ini yang paling
     * sering dicari mata saat menyapu daftar: "yang mana tadi feeder-nya".
     */
    private val PREFIX = mapOf(
        CableType.BACKBONE to "BB",
        CableType.FEEDER to "FDR",
        CableType.DISTRIBUTION to "DIST",
        CableType.DROP to "DROP",
    )

    /** Ruas antara dua simpul berkode — bentuk paling lengkap dan paling sering terpakai. */
    fun between(type: CableType, from: String, to: String): String = assemble(type, listOf(from, to))

    /**
     * Ruas yang cuma satu ujungnya berkode. Terjadi pada drop ke pelanggan: kode pelanggan
     * milik modul lain dan tak boleh ditarik ke sini, jadi [tail] dipakai sebagai pembeda —
     * nomor slot ODP asalnya bila diketahui (drop-nya jadi tertelusur dari kotaknya), atau
     * potongan pengenal bila tidak.
     */
    fun anchored(type: CableType, anchor: String, tail: String): String = assemble(type, listOf(anchor, tail))

    /** Jalan terakhir: kedua ujungnya tak menyumbang kode apa pun. */
    fun anonymous(type: CableType, tail: String): String = assemble(type, listOf(tail))

    /**
     * Varian ke-[n] dari sebuah kode yang ternyata sudah dipakai. Ekornya dipotong lebih dulu
     * supaya sisipan angka tak mendorong kode melewati batas panjang.
     */
    fun withSuffix(base: String, n: Int): String {
        val suffix = "-$n"
        return base.take(MAX_LENGTH - suffix.length).trimEnd('-', '.', '/', '_') + suffix
    }

    private fun assemble(type: CableType, parts: List<String>): String {
        val prefix = PREFIX.getValue(type)
        val cleaned = parts.map(::clean).filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return prefix
        // Jatah panjang dibagi rata: kode ujung yang panjang dipangkas dari DEPAN, sebab
        // bagian yang membedakan satu kotak dari tetangganya hampir selalu di belakang
        // ("ODP-MELATI-BLOK-C-012" → yang penting "BLOK-C-012", bukan "ODP-MELAT").
        val budget = MAX_LENGTH - prefix.length - cleaned.size
        val perPart = (budget / cleaned.size).coerceAtLeast(1)
        val trimmed = cleaned.map { it.takeLast(perPart).trim('-') }.filter { it.isNotEmpty() }
        return (listOf(prefix) + trimmed).joinToString("-")
    }

    /** Kode ujung dinormalkan ke aksara yang diterima [AssetNaming]; sisanya jadi pemisah. */
    private fun clean(raw: String): String = raw.uppercase()
        .replace(Regex("[^A-Z0-9._/-]"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-')
}
