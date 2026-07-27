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

    /** Baris `ifconfig-push` IP overlay tetap untuk peer (dipanggil `client-connect`). Null = tolak. */
    fun clientConnectLine(rawToken: String, username: String): String?
}
