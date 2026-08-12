package com.duluin.ftth.vpn.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bawaan hub VPN (nilai dev; ditimpa lewat environment di prod). Dipakai
 * `VpnServerService.create` saat pemanggil tidak menyertakan port/protokol/subnet.
 */
@ConfigurationProperties(prefix = "ftth.vpn")
data class VpnProperties(
    val defaultPort: Int = 1194,
    /**
     * TCP, bukan UDP: klien OpenVPN RouterOS v6 TCP-only, dan hub yang lahir UDP diam-diam
     * menutup pintu bagi seluruh perangkat v6 di lapangan. Lihat `VpnProtocol`.
     */
    val defaultProtocol: String = "TCP",
    val defaultTunnelCidr: String = "10.8.0.0/24",
    /**
     * URL publik aplikasi yang di-embed ke installer (dipakai VPS untuk callback verify/connect)
     * dan untuk merangkai perintah pasang. Kosong = diturunkan dari request unduh installer.
     */
    val publicBaseUrl: String = "",
    /**
     * Rentang port publik TCP di hub yang di-DNAT ke Winbox tiap perangkat (satu port unik per
     * akun/hub). Operator meremote perangkat lewat `IP_HUB:port` tanpa ikut men-dial tunnel.
     * Buka rentang ini di firewall VPS (mis. `20000-40000/tcp`).
     */
    val remotePortMin: Int = 20000,
    val remotePortMax: Int = 40000,
)
