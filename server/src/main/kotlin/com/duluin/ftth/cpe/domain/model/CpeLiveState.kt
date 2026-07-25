package com.duluin.ftth.cpe.domain.model

/**
 * Keadaan langsung sebuah CPE yang dibaca dari ACS saat dibutuhkan, bukan dari
 * proyeksi tersimpan. Nilainya cepat berubah (host datang-pergi, WiFi diubah dari
 * app vendor), jadi menyimpannya hanya menyajikan yang usang.
 */

/**
 * Satu jaringan WiFi pada CPE — satu instance `WLANConfiguration` di TR-069.
 *
 * Passphrase tidak selalu terbaca: sebagian firmware menandai parameter kunci
 * sebagai tak-terbaca demi keamanan, sehingga ACS mengembalikannya kosong. Itu
 * kondisi wajar, bukan error — UI menampilkan "tersembunyi".
 */
data class WifiNetwork(
    /**
     * Path instance TR-069 jaringan ini (mis. "InternetGatewayDevice.LANDevice.1.
     * WLANConfiguration.1"), dipakai untuk menyasar perubahan kembali ke parameter
     * yang tepat. Detail pengalamatan ACS — tak ditampilkan ke pengguna.
     */
    val ref: String,
    val ssid: String,
    val passphrase: String?,
    /** Pita frekuensi bila terbaca (mis. "2.4GHz"/"5GHz"); null bila perangkat diam. */
    val band: String?,
    val enabled: Boolean,
)

/** Satu perangkat yang sedang tersambung ke LAN CPE — dari tabel `Hosts` TR-069. */
data class ConnectedHost(
    val hostName: String?,
    val ipAddress: String?,
    val macAddress: String?,
    val active: Boolean,
)
