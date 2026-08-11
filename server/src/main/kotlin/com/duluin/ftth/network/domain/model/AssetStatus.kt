package com.duluin.ftth.network.domain.model

/**
 * Siklus hidup aset fisik jaringan.
 *
 * [PLANNED] penting untuk perencanaan: aset sudah digambar di peta dan boleh
 * dihitung dalam kapasitas rencana, tapi belum boleh dipasangi pelanggan.
 *
 * [ABANDONED] adalah kejujuran tentang barang yang MASIH ADA tapi sudah tak
 * dipakai — paling sering kabel drop bekas pelanggan yang cabut. Menghapusnya
 * dari data salah: seratnya masih tergantung di tiang, masih bisa tersangkut
 * bucket truk, dan masih terlihat teknisi yang datang ke situ tiga bulan lagi.
 * Membiarkannya berstatus aktif juga salah: ia akan ikut terhitung sebagai
 * kabel siap pakai, dan orang akan merencanakan pelanggan baru di atasnya.
 * Karena itu ia punya statusnya sendiri, bukan dipaksa jadi [INACTIVE] yang
 * berarti "sengaja dimatikan, sewaktu-waktu dinyalakan lagi".
 */
enum class AssetStatus {
    PLANNED,
    ACTIVE,
    MAINTENANCE,
    INACTIVE,

    /** Fisiknya masih terpasang, tapi sudah tak dipakai dan tak akan dipakai lagi. */
    ABANDONED,
    ;

    /** Hanya aset aktif yang boleh menerima sambungan pelanggan baru. */
    fun acceptsService(): Boolean = this == ACTIVE
}
