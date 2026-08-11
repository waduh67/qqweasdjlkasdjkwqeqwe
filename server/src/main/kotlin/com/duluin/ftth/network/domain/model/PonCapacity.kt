package com.duluin.ftth.network.domain.model

/**
 * Batas jumlah ONU yang boleh menggantung di SATU port PON.
 *
 * Ini bukan batas administratif yang bisa dinegosiasikan, melainkan sifat GPON
 * itu sendiri (ITU-T G.984): satu port menyiarkan ke bawah dan menjadwalkan
 * giliran bicara ke atas untuk sekumpulan ONU yang berbagi serat yang sama.
 * Jumlahnya dibatasi lebar jendela penjadwalan, dan yang ke-65 tidak "pelan" —
 * ia tidak pernah dapat giliran sama sekali.
 *
 * Yang membuat batas ini gampang tertabrak: tak ada satu pun layar yang
 * menunjukkannya. Kabinet ditambah satu per satu, tiap penambahan terasa kecil,
 * dan angka 64 baru ketahuan saat ONU pelanggan baru tak mau daftar di lapangan —
 * dengan teknisi sudah berdiri di rumahnya.
 */
object PonCapacity {

    /** Plafon keras satu port PON pada GPON. */
    const val GPON_MAX_ONU = 64

    /**
     * Ambang peringatan: sisa satu ODP penuh (8 port) sebelum plafon.
     *
     * Dipilih segitu karena satuan pertumbuhan di lapangan memang satu ODP, bukan
     * satu pelanggan. Peringatan yang baru muncul di angka 63 datang terlambat —
     * yang perlu diperingatkan adalah orang yang sedang MERENCANAKAN ODP
     * berikutnya, sebab ODP itu yang akan menembus batasnya.
     */
    const val WARN_AT_ONU = GPON_MAX_ONU - 8

    /** Sudah pantas diperingatkan (termasuk yang sudah lewat batas). */
    fun crowded(onuCount: Int): Boolean = onuCount >= WARN_AT_ONU

    fun overflowing(onuCount: Int): Boolean = onuCount > GPON_MAX_ONU
}
