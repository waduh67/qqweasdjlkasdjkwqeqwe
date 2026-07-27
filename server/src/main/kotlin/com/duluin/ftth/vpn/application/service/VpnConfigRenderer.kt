package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.vpn.application.port.inbound.ServerConfigView
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.springframework.stereotype.Component

/**
 * Perakit teks konfigurasi OpenVPN (murni, tanpa I/O). Baseline RouterOS v7 — sejalan
 * dengan adapter Mikrotik REST v7 di module bng. Menerima entitas dengan rahasianya sudah
 * terdekripsi (CA/tls-auth server, password peer) dan mengembalikan artefak siap-unduh.
 */
@Component
class VpnConfigRenderer {

    /**
     * Berkas `.ovpn` klien generik yang mandiri (CA + kredensial inline). Melempar
     * [ConflictException] bila hub belum punya sertifikat CA — mustahil membangun config
     * klien tanpanya.
     */
    fun renderOvpn(server: VpnServer, peer: VpnPeer): String {
        val caCert = server.caCertPem
            ?: throw ConflictException("Server VPN belum punya sertifikat CA")
        return buildString {
            appendLine("client")
            appendLine("dev tun")
            appendLine("proto ${server.protocol.name.lowercase()}")
            appendLine("remote ${server.host} ${server.port}")
            appendLine("resolv-retry infinite")
            appendLine("nobind")
            appendLine("persist-key")
            appendLine("persist-tun")
            appendLine("remote-cert-tls server")
            appendLine("auth-nocache")
            appendLine("cipher AES-256-GCM")
            appendLine("verb 3")
            appendLine("<auth-user-pass>")
            appendLine(peer.username)
            appendLine(peer.password)
            appendLine("</auth-user-pass>")
            appendLine("<ca>")
            appendLine(caCert.trim())
            appendLine("</ca>")
            server.tlsAuthKey?.let { tlsAuth ->
                appendLine("key-direction 1")
                appendLine("<tls-auth>")
                appendLine(tlsAuth.trim())
                appendLine("</tls-auth>")
            }
        }
    }

    /** Skrip RouterOS v7 untuk membuat interface ovpn-client ke hub. */
    fun renderRouterOs(server: VpnServer, peer: VpnPeer): String = buildString {
        appendLine("# OpenVPN management client -> ${server.name} (${server.host}:${server.port})")
        appendLine("# Overlay IP (di-push server): ${peer.overlayIp}")
        appendLine("/interface/ovpn-client/add \\")
        appendLine("    name=\"ovpn-${peer.username}\" \\")
        appendLine("    connect-to=${server.host} \\")
        appendLine("    port=${server.port} \\")
        appendLine("    protocol=${server.protocol.name.lowercase()} \\")
        appendLine("    user=\"${peer.username}\" \\")
        appendLine("    password=\"${peer.password}\" \\")
        appendLine("    mode=ip \\")
        appendLine("    add-default-route=no \\")
        appendLine("    use-peer-dns=no \\")
        appendLine("    verify-server-certificate=no \\")
        appendLine("    cipher=aes256-gcm \\")
        appendLine("    disabled=no")
    }

    /**
     * `server.conf` + berkas client-config-dir (CCD) per peer AKTIF (ENABLED). Tiap CCD
     * berisi `ifconfig-push {overlayIp} {netmask}` agar peer selalu mendapat IP tetap. Bila
     * CA belum di-set, blok `<ca>` diganti komentar penanda (server tetap bisa dirender).
     */
    fun renderServerConfig(server: VpnServer, peers: List<VpnPeer>): ServerConfigView {
        val subnet = TunnelSubnet.parse(server.tunnelCidr)
        val netmask = subnet.netmask()
        val serverConf = buildString {
            appendLine("port ${server.port}")
            appendLine("proto ${server.protocol.name.lowercase()}")
            appendLine("dev tun")
            appendLine("topology subnet")
            appendLine("server ${subnet.networkAddress()} $netmask")
            appendLine("client-config-dir ccd")
            appendLine("username-as-common-name")
            appendLine("verify-client-cert none")
            appendLine("keepalive 10 120")
            appendLine("persist-key")
            appendLine("persist-tun")
            appendLine("cipher AES-256-GCM")
            appendLine("verb 3")
            appendLine("# Sertifikat/kunci server + dh WAJIB disediakan operator (easy-rsa).")
            val caCert = server.caCertPem
            if (caCert == null) {
                appendLine("# CA belum di-set")
            } else {
                appendLine("<ca>")
                appendLine(caCert.trim())
                appendLine("</ca>")
            }
        }
        val ccd = peers
            .filter { it.status == VpnPeerStatus.ENABLED }
            .associate { it.username to "ifconfig-push ${it.overlayIp} $netmask" }
        return ServerConfigView(serverConf = serverConf, ccd = ccd)
    }
}
