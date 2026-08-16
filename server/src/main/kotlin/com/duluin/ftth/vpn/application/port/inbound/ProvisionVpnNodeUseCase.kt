package com.duluin.ftth.vpn.application.port.inbound

/**
 * Provisioning hub dari sisi VPS, diautentikasi dengan token node (bukan bearer JWT). Dipanggil
 * oleh installer satu-perintah dan skrip callback OpenVPN. Token di-resolve ke tenant+hub
 * SEBELUM context tenant dipasang (tabel token tanpa RLS), lalu pembacaan hub/peer dilakukan
 * dalam scope tenant tersebut. Semua method mengembalikan hasil "gagal-aman" bila token invalid.
 */
interface ProvisionVpnNodeUseCase {

    /** Skrip installer bash lengkap untuk hub pemilik [rawToken]; [appBaseUrl] di-embed untuk callback. */
    fun renderInstaller(rawToken: String, appBaseUrl: String): String

    /** Verifikasi username/password satu peer (dipanggil `auth-user-pass-verify`). False bila tak valid. */
    fun authenticate(rawToken: String, username: String, password: String): Boolean

    /**
     * Config sesi peer (dipanggil `client-connect`): baris pertama `ifconfig-push` IP overlay
     * tetap, disusul baris `iroute` per blok di belakang peer. Null = tolak.
     */
    fun clientConnectLine(rawToken: String, username: String): String?

    /**
     * Tabel penerusan port seluruh hub untuk direkonsiliasi VPS (dipanggil timer `ftth-sync`).
     * Null = token tak dikenal; VPS memperlakukannya sebagai "jangan sentuh iptables".
     */
    fun forwardTable(rawToken: String): String?

    /**
     * Daftar blok di belakang peer seluruh hub untuk direkonsiliasi VPS (timer `ftth-sync`):
     * rute kernel + NAT/FORWARD-nya. Null = token tak dikenal → VPS tak menyentuh apa pun.
     */
    fun routeTable(rawToken: String): String?

    /** Telemetri: hub melapor peer terhubung → tandai online + catat waktu. False bila token/peer tak dikenal. */
    fun reportConnected(rawToken: String, username: String): Boolean

    /** Telemetri: hub melapor peer putus → tandai offline. False bila token/peer tak dikenal. */
    fun reportDisconnected(rawToken: String, username: String): Boolean
}
