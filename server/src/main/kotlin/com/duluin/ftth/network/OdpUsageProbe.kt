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

    /**
     * Versi banyak-ODP, untuk pertanyaan yang lingkupnya sekumpulan kotak —
     * mis. "berapa ONU yang menggantung di seluruh ODP di bawah port PON ini".
     *
     * Bawaannya menanya satu per satu supaya implementasi lama tetap sah tanpa
     * diubah; yang punya query agregat menimpanya. Satu port PON bisa menaungi
     * puluhan ODP, dan hitungan yang N+1 di situ berarti panel muatannya jadi
     * lambat justru pada jaringan yang paling perlu diperiksa.
     */
    fun countAttachedTo(odpIds: Set<UUID>): Map<UUID, Long> =
        odpIds.associateWith { countAttachedTo(it) }

    /**
     * Nomor port yang sedang DITEMPATI di sebuah ODP — bukan sekadar berapa
     * banyak. Yang bertanya adalah pengecilan kapasitas: kotak 16 port berisi tiga
     * pelanggan boleh saja mereka duduk di port 1, 2, dan 12, jadi hitungan "tiga"
     * tak bisa menjawab apakah kotaknya masih boleh diperkecil jadi 8.
     *
     * Sengaja tanpa nilai bawaan, tak seperti [countAttachedTo] versi banyak-ODP:
     * probe yang diam-diam menjawab "kosong" akan meloloskan pengecilan yang
     * membuat pelanggan di port 12 lenyap dari kotak yang cuma mengaku punya 8.
     */
    fun occupiedPortsOn(odpId: UUID): Set<Int>

    /**
     * SIAPA yang menempati tiap port, bukan sekadar port mana yang terisi.
     *
     * Yang bertanya adalah meja sambung: teknisi yang membuka ODP perlu tahu kaki
     * mana melayani rumah siapa, dan itu tak bisa dijawab module network sendirian
     * — nama pelanggan dan serial ONU ada di module customer. Sekaligus bahan
     * pembanding untuk mencari beda antara catatan dan serat yang sesungguhnya
     * terpasang.
     *
     * Bernilai bawaan kosong, tak seperti [occupiedPortsOn]: probe yang diam di
     * sini cuma membuat kolom penghuninya kosong — tak ada keputusan merusak yang
     * diambil dari jawabannya.
     */
    fun occupantsOf(odpId: UUID): List<OdpPortOccupant> = emptyList()

    /** Sebutan untuk pesan galat, mis. "ONU pelanggan". */
    fun describeUsage(): String
}

/**
 * Penghuni sebuah port ODP menurut CATATAN — pemasangan ONU yang dibukukan
 * orang, bukan serat yang tertelusur.
 *
 * Bedanya dengan serat itulah yang penting: catatan bisa mendahului pekerjaan
 * (ONU didaftarkan di port 1, kaki 1 belum dilas), bisa juga ketinggalan (drop
 * dipindah ke kaki lain, catatannya tetap). Karena itu kedua sisi disimpan
 * terpisah dan dibandingkan, bukan salah satunya dianggap benar.
 *
 * @param portNumber null = ONU tercatat di ODP ini tapi belum ditempelkan ke port
 *   mana pun — barang sudah di lokasi, pemasangannya belum tuntas.
 */
data class OdpPortOccupant(
    val portNumber: Int?,
    val customerId: UUID,
    val customerName: String,
    val onuSerialNumber: String,
    /** Nama enum apa adanya (mis. "ONLINE"); pelabelan urusan lapisan tampilan. */
    val onuStatus: String,
    val opticalHealth: String,
    val rxPowerDbm: Double?,
)
