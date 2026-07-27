package com.duluin.ftth.vpn.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bawaan hub VPN (nilai dev; ditimpa lewat environment di prod). Dipakai
 * `VpnServerService.create` saat pemanggil tidak menyertakan port/protokol/subnet.
 */
@ConfigurationProperties(prefix = "ftth.vpn")
data class VpnProperties(
    val defaultPort: Int = 1194,
    val defaultProtocol: String = "UDP",
    val defaultTunnelCidr: String = "10.8.0.0/24",
    /**
     * URL publik aplikasi yang di-embed ke installer (dipakai VPS untuk callback verify/connect)
     * dan untuk merangkai perintah pasang. Kosong = diturunkan dari request unduh installer.
     */
    val publicBaseUrl: String = "",
)
