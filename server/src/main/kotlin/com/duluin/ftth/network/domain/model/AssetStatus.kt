package com.duluin.ftth.network.domain.model

/**
 * Siklus hidup aset fisik jaringan.
 *
 * [PLANNED] penting untuk perencanaan: aset sudah digambar di peta dan boleh
 * dihitung dalam kapasitas rencana, tapi belum boleh dipasangi pelanggan.
 */
enum class AssetStatus {
    PLANNED,
    ACTIVE,
    MAINTENANCE,
    INACTIVE,
    ;

    /** Hanya aset aktif yang boleh menerima sambungan pelanggan baru. */
    fun acceptsService(): Boolean = this == ACTIVE
}
