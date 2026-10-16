package com.karuhun.ftth.vpn.domain.model

/**
 * Profil kemampuan klien OpenVPN target yang di-render config-nya.
 *
 * - [V7] — RouterOS v7: UDP/TCP, AES-256-CBC dan sintaks menu `/interface/ovpn-client`.
 * - [V6] — RouterOS v6: TCP, AES-256-CBC dan sintaks menu `/interface ovpn-client`.
 *
 * RouterOS v7 juga tidak menjamin negosiasi cipher (NCP). Kedua profil harus memakai
 * cipher fallback hub. Klien OpenVPN yang mendukung NCP tetap dapat menegosiasikan GCM.
 * https://help.mikrotik.com/docs/spaces/ROS/pages/2031655/OpenVPN
 */
enum class VpnClientVariant(val cipher: String, val routerOsCipher: String) {
    V7(cipher = "AES-256-CBC", routerOsCipher = "aes256-cbc"),

    /**
     * [routerOsCipher] SENGAJA `aes256`, bukan `aes256-cbc`. Ejaan bermode (`aes256-cbc`,
     * `aes256-gcm`) baru lahir di RouterOS v7 ketika GCM ikut didukung; v6 yang cuma punya CBC
     * menamainya `aes256` saja (di WinBox tertulis "aes 256"). Menempelkan ejaan v7 ke v6
     * berbuah `syntax error` — perangkatnya menolak nilainya, bukan propertinya. Di berkas
     * `.ovpn` tetap `AES-256-CBC`: itu nama versi OpenVPN, bukan nama versi RouterOS.
     */
    V6(cipher = "AES-256-CBC", routerOsCipher = "aes256"),
}
