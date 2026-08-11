package com.duluin.ftth.network.application.port.inbound

import java.util.UUID

/**
 * "Rantai ODP → ODP ini beneran atau cuma penyamar?"
 *
 * Sebelum sambungan dicatat per core, satu-satunya cara menggambar selubung 8
 * core yang lewat di depan delapan kotak adalah memecahnya jadi delapan kabel
 * berantai: ODC → ODP-1, ODP-1 → ODP-2, ODP-2 → ODP-3, dan seterusnya. Di peta
 * hasilnya mirip; di lapangan itu SATU kabel yang dikupas di tiap kotak.
 *
 * Bedanya bukan main-main. Rantai palsu membuat:
 *
 * - panjang material dihitung berlebih (tiap ruas menambah slack-nya sendiri);
 * - simulasi putus salah — memotong ruas ke-3 seolah tak memadamkan kotak ke-4,
 *   padahal seratnya sama;
 * - "kotak ini disuapi kabel mana" menunjuk kabel yang secara fisik tak pernah
 *   ada nomornya di haspel mana pun.
 *
 * Tapi ODP → ODP yang SUNGGUHAN juga ada: splitter bertingkat, ketika kaki
 * splitter di kotak pertama menyuapi splitter di kotak berikutnya. Bentuk
 * gambarnya identik, jadi jenis kabel tak bisa membedakannya — yang membedakan
 * adalah baris sambungannya. Itulah yang dibaca pemeriksaan ini.
 *
 * Ia sengaja TIDAK menghapus apa pun sendiri. Menyatukan dua ruas jadi satu
 * berarti membuang core beserta sambungan yang menempel padanya, dan keputusan
 * seperti itu milik orang yang tahu keadaan di lapangan.
 */
interface CheckCableChainUseCase {

    fun check(cableId: UUID): CableChainView
}

/** Kesimpulan pemeriksaan rantai — lihat [CheckCableChainUseCase]. */
enum class ChainVerdict(val label: String) {
    /** Bukan ruas ODP → ODP; tak ada yang perlu diperiksa. */
    NOT_CHAINED("Bukan rantai"),

    /** Kaki splitter kotak hulu benar-benar menyuapi kabel ini. */
    CASCADE("Splitter bertingkat"),

    /** Tanda-tanda satu selubung yang dipecah jadi beberapa ruas. */
    SUSPECT("Diduga selubung yang dipecah"),

    /** Catatannya belum cukup untuk menyimpulkan apa pun. */
    UNKNOWN("Belum bisa dipastikan"),
}

data class CableChainView(
    val cableId: UUID,
    val verdict: ChainVerdict,
    /** Satu kalimat kesimpulan, siap ditempel di panel. */
    val headline: String,
    /** Alasan yang bisa dicek sendiri oleh orang yang membacanya. */
    val evidence: List<String>,
    /** Langkah yang disarankan; null bila memang tak ada yang perlu dikerjakan. */
    val suggestion: String? = null,
    /** Kotak hulu tempat rantai ini bersambung. */
    val upstreamClosureCode: String? = null,
    /** Kabel yang masuk ke kotak hulu itu — calon "selubung sesungguhnya". */
    val upstreamCableId: UUID? = null,
    val upstreamCableCode: String? = null,
    /** Kaki splitter yang terbukti menyuapi kabel ini (kosong bila tak ada). */
    val cascadeLegs: List<Int> = emptyList(),
)
