package com.duluin.ftth.vpn.domain.model

/**
 * Profil kemampuan klien OpenVPN target yang di-render config-nya.
 *
 * - [V7] — RouterOS v7 (atau OpenVPN modern): UDP/TCP + **AES-256-GCM**, mendukung NCP dan
 *   punya properti `verify-server-certificate`.
 * - [V6] — RouterOS v6: **TCP-only** + **AES-256-CBC** (tanpa NCP), tanpa
 *   `verify-server-certificate`, dan memakai sintaks menu lama (`/interface ovpn-client add`).
 *
 * Satu hub melayani KEDUANYA: `server.conf` menyajikan GCM+CBC via `data-ciphers` +
 * `data-ciphers-fallback`, jadi klien v7 nego GCM dan klien v6 jatuh ke CBC. Karena v6
 * TCP-only, varian [V6] hanya bermakna pada hub berprotokol TCP.
 */
enum class VpnClientVariant(val cipher: String, val routerOsCipher: String) {
    V7(cipher = "AES-256-GCM", routerOsCipher = "aes256-gcm"),

    /**
     * [routerOsCipher] SENGAJA `aes256`, bukan `aes256-cbc`. Ejaan bermode (`aes256-cbc`,
     * `aes256-gcm`) baru lahir di RouterOS v7 ketika GCM ikut didukung; v6 yang cuma punya CBC
     * menamainya `aes256` saja (di WinBox tertulis "aes 256"). Menempelkan ejaan v7 ke v6
     * berbuah `syntax error` — perangkatnya menolak nilainya, bukan propertinya. Di berkas
     * `.ovpn` tetap `AES-256-CBC`: itu nama versi OpenVPN, bukan nama versi RouterOS.
     */
    V6(cipher = "AES-256-CBC", routerOsCipher = "aes256"),
}
