package com.duluin.ftth.vpn

/**
 * Kontrak publik modul `vpn` untuk modul lain.
 *
 * Yang dibocorkan hanya BENTUK overlay-nya — blok tunnel dan alamat hub di dalamnya —
 * bukan satu pun kredensial (CA, kunci server, config peer). Cukup untuk modul lain
 * menjawab pertanyaan "perangkat di alamat ini masuk lewat tunnel yang mana, dan
 * alamat berapa yang ia lihat sebagai kami?".
 *
 * Pertanyaan itu nyata dan menggigit: router yang men-dial hub melihat kita sebagai
 * alamat overlay hub, bukan IP publik VPS. Menyuruhnya menembak IP publik berarti
 * paketnya datang dari IP publik RUMAH pelanggan yang tak terdaftar sebagai klien —
 * dan FreeRADIUS mengabaikan klien tak dikenal tanpa membalas apa pun, jadi yang
 * terlihat di router cuma "timeout" tanpa sebab.
 */
interface VpnApi {

    /**
     * Hub overlay yang sedang melayani, satu entri per blok tunnel. Kosong bila fitur
     * VPN belum dipakai sama sekali.
     */
    fun overlayTunnels(): List<VpnTunnelRef>

    /**
     * Blok overlay yang memuat [address], atau null bila alamat itu bukan penghuni tunnel
     * mana pun — IP publik, alamat privat di balik NAT, nama host, atau isian yang belum
     * berbentuk alamat.
     *
     * Dipakai modul lain untuk menjawab "perangkat ini bisa kami hubungi balik atau tidak?"
     * tanpa ikut menghitung sendiri aritmetika CIDR-nya.
     */
    fun tunnelContaining(address: String): VpnTunnelRef?
}

/**
 * Satu blok overlay: [tunnelCidr] rentangnya, [serverAddress] alamat hub di dalam
 * rentang itu (network+1) — alamat yang peer pakai untuk menghubungi layanan kita.
 */
data class VpnTunnelRef(
    val tunnelCidr: String,
    val serverAddress: String,
)
