package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.vpn.application.port.inbound.ServerConfigView
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnClientVariant
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * Perakit teks konfigurasi OpenVPN (murni, tanpa I/O). Baseline RouterOS v7 (UDP/TCP +
 * AES-256-GCM) — sejalan dengan adapter Mikrotik REST v7 di module bng — plus varian
 * RouterOS v6 (TCP + AES-256-CBC) untuk perangkat lama; satu hub melayani keduanya karena
 * `server.conf` menyajikan GCM+CBC via `data-ciphers`. Menerima entitas dengan rahasianya
 * sudah terdekripsi (CA/tls-auth server, password peer) dan mengembalikan artefak siap-unduh.
 */
@Component
class VpnConfigRenderer {

    /**
     * Berkas `.ovpn` klien generik yang mandiri (CA + kredensial inline). [variant] menentukan
     * cipher: [VpnClientVariant.V7] (AES-256-GCM) atau [VpnClientVariant.V6] (AES-256-CBC, dan
     * proto dipaksa `tcp` karena v6 TCP-only). Melempar [ConflictException] bila hub belum punya
     * sertifikat CA — mustahil membangun config klien tanpanya — atau bila varian [VpnClientVariant.V6]
     * diminta pada hub non-TCP.
     */
    fun renderOvpn(server: VpnServer, peer: VpnPeer, variant: VpnClientVariant = VpnClientVariant.V7): String {
        val caCert = server.caCertPem
            ?: throw ConflictException("Server VPN belum punya sertifikat CA")
        requireVariantSupported(server, variant)
        val proto = if (variant == VpnClientVariant.V6) "tcp" else server.protocol.name.lowercase()
        return buildString {
            appendLine("client")
            appendLine("dev tun")
            appendLine("proto $proto")
            appendLine("remote ${server.host} ${server.port}")
            appendLine("resolv-retry infinite")
            appendLine("nobind")
            appendLine("persist-key")
            appendLine("persist-tun")
            appendLine("remote-cert-tls server")
            appendLine("auth-nocache")
            appendLine("cipher ${variant.cipher}")
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

    /**
     * Parameter `ovpn-client` RouterOS **v7** sebagai pasangan key=value URUT — sumber tunggal
     * untuk skrip `.rsc` [renderRouterOs] maupun perintah satu-baris [renderRouterOsCommand].
     * Memakai AES-256-GCM (dinego lewat NCP hub). Untuk perangkat v6 lihat [routerOsV6Params].
     * Nilai username/password dikutip; keduanya dijamin alfanumerik oleh PasswordGenerator/slug.
     */
    private fun routerOsParams(server: VpnServer, peer: VpnPeer): List<Pair<String, String>> = listOf(
        "name" to "\"ovpn-${peer.username}\"",
        "connect-to" to server.host,
        "port" to server.port.toString(),
        "protocol" to server.protocol.name.lowercase(),
        "user" to "\"${peer.username}\"",
        "password" to "\"${peer.password}\"",
        "mode" to "ip",
        "add-default-route" to "no",
        "use-peer-dns" to "no",
        "verify-server-certificate" to "no",
        "auth" to "sha1",
        "cipher" to "aes256-gcm",
        "disabled" to "no",
    )

    /** Skrip RouterOS v7 (multi-baris, untuk diunduh `.rsc`) yang membuat interface ovpn-client ke hub. */
    fun renderRouterOs(server: VpnServer, peer: VpnPeer): String = buildString {
        appendLine("# OpenVPN management client -> ${server.name} (${server.host}:${server.port})")
        appendLine("# Overlay IP (di-push server): ${peer.overlayIp} — butuh RouterOS v7")
        appendLine("/interface/ovpn-client/add \\")
        val params = routerOsParams(server, peer)
        params.forEachIndexed { i, (key, value) ->
            appendLine("    $key=$value${if (i == params.lastIndex) "" else " \\"}")
        }
    }

    /**
     * Perintah RouterOS v7 **satu-baris** siap salin-tempel di terminal Mikrotik (sudah berisi
     * password). Isinya sama dengan [renderRouterOs] tapi tanpa continuation `\`, jadi tempel
     * sekali langsung jalan tanpa risiko baris kepotong.
     */
    fun renderRouterOsCommand(server: VpnServer, peer: VpnPeer): String =
        routerOsParams(server, peer).joinToString(
            separator = " ",
            prefix = "/interface/ovpn-client/add ",
        ) { (key, value) -> "$key=$value" }

    /**
     * Parameter `ovpn-client` RouterOS **v6** URUT. v6 memakai sintaks menu lama
     * (`/interface ovpn-client add`, spasi bukan slash), **TCP-only** (tanpa properti `protocol`),
     * cipher `aes256-cbc` (tanpa GCM/NCP), dan **tanpa** `verify-server-certificate` (properti itu
     * tak ada di v6). `certificate=none` → dial tanpa sertifikat klien (hub memakai
     * `verify-client-cert none`). Properti yang tak pasti ada di v6 (mis. `use-peer-dns`) sengaja
     * DIHILANGKAN agar perintah tak gagal total karena satu properti asing.
     */
    private fun routerOsV6Params(server: VpnServer, peer: VpnPeer): List<Pair<String, String>> = listOf(
        "name" to "\"ovpn-${peer.username}\"",
        "connect-to" to server.host,
        "port" to server.port.toString(),
        "user" to "\"${peer.username}\"",
        "password" to "\"${peer.password}\"",
        "mode" to "ip",
        "certificate" to "none",
        "auth" to "sha1",
        "cipher" to VpnClientVariant.V6.routerOsCipher,
        "add-default-route" to "no",
        "disabled" to "no",
    )

    /**
     * Skrip RouterOS **v6** (multi-baris, `.rsc`) untuk perangkat lama. Best-effort: sintaks/cipher
     * v6 bervariasi antar-rilis dan hanya bisa dipastikan di perangkat asli — komentar kepala
     * mengingatkan itu. Melempar [ConflictException] bila hub bukan TCP (v6 mustahil dial UDP).
     */
    fun renderRouterOsV6(server: VpnServer, peer: VpnPeer): String {
        requireVariantSupported(server, VpnClientVariant.V6)
        return buildString {
            appendLine("# OpenVPN management client (RouterOS v6) -> ${server.name} (${server.host}:${server.port})")
            appendLine("# v6: TCP + AES-256-CBC. Overlay IP (di-push server): ${peer.overlayIp}")
            appendLine("# Best-effort — bila v6 menolak properti tertentu, sesuaikan dengan rilis RouterOS Anda.")
            appendLine("/interface ovpn-client add \\")
            val params = routerOsV6Params(server, peer)
            params.forEachIndexed { i, (key, value) ->
                appendLine("    $key=$value${if (i == params.lastIndex) "" else " \\"}")
            }
        }
    }

    /**
     * Perintah RouterOS **v6** satu-baris siap salin-tempel. Isinya sama dengan [renderRouterOsV6]
     * tanpa continuation `\`. Melempar [ConflictException] bila hub bukan TCP.
     */
    fun renderRouterOsCommandV6(server: VpnServer, peer: VpnPeer): String {
        requireVariantSupported(server, VpnClientVariant.V6)
        return routerOsV6Params(server, peer).joinToString(
            separator = " ",
            prefix = "/interface ovpn-client add ",
        ) { (key, value) -> "$key=$value" }
    }

    /** v6 (TCP-only) tak bisa dial hub UDP: tolak dengan pesan jelas alih-alih merender config yang mustahil connect. */
    private fun requireVariantSupported(server: VpnServer, variant: VpnClientVariant) {
        if (variant == VpnClientVariant.V6 && server.protocol != VpnProtocol.TCP) {
            throw ConflictException(
                "RouterOS v6 butuh hub TCP; hub '${server.name}' memakai ${server.protocol.name}. " +
                    "Pakai varian v7 atau buat hub TCP.",
            )
        }
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
            // NCP: v7 nego AES-256-GCM; klien tanpa NCP (RouterOS v6) jatuh ke AES-256-CBC.
            appendLine("data-ciphers AES-256-GCM:AES-256-CBC")
            appendLine("data-ciphers-fallback AES-256-CBC")
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

    /**
     * Installer satu-perintah untuk VPS. Menyisipkan CA + sertifikat/kunci server (dari PKI
     * aplikasi) dan `server.conf` model callback-langsung ke template bash: OpenVPN memverifikasi
     * user/pass dan mengunci IP overlay dengan memanggil balik aplikasi memakai [rawNodeToken],
     * jadi perangkat baru bekerja seketika tanpa menyentuh VPS lagi. Melempar [ConflictException]
     * bila PKI hub belum lengkap.
     */
    fun renderInstallScript(server: VpnServer, appBaseUrl: String, rawNodeToken: String): String {
        val caCert = server.caCertPem
        val serverCert = server.serverCertPem
        val serverKey = server.serverKeyPem
        if (caCert == null || serverCert == null || serverKey == null) {
            throw ConflictException("Hub '${server.name}' belum siap: PKI (CA/sertifikat server) belum terbit")
        }
        val baseUrl = appBaseUrl.trimEnd('/')
        val proto = server.protocol.name.lowercase()
        return installTemplate
            .replace("{{SERVER_NAME}}", server.name)
            .replace("{{HOST}}", server.host)
            .replace("{{PORT}}", server.port.toString())
            .replace("{{PROTO}}", proto)
            .replace("{{APP_URL}}", baseUrl)
            .replace("{{NODE_TOKEN}}", rawNodeToken)
            .replace("{{TUNNEL_CIDR}}", TunnelSubnet.parse(server.tunnelCidr).cidr)
            .replace("{{DEVICE_PORT}}", DEVICE_PORT.toString())
            .replace("{{SERVER_CONF}}", renderNodeServerConf(server, proto))
            .replace("{{CA_CERT}}", caCert.trim())
            .replace("{{SERVER_CERT}}", serverCert.trim())
            .replace("{{SERVER_KEY}}", serverKey.trim())
    }

    /**
     * `server.conf` untuk model callback-langsung: sertifikat/kunci dari berkas, autentikasi
     * user/pass dan `ifconfig-push` IP tetap didelegasikan ke skrip yang memanggil balik aplikasi
     * (`auth-user-pass-verify` + `client-connect`). `client-disconnect` melepas DNAT port publik
     * saat perangkat putus. `dh none` memakai ECDHE (kunci server RSA).
     */
    private fun renderNodeServerConf(server: VpnServer, proto: String): String {
        val subnet = TunnelSubnet.parse(server.tunnelCidr)
        return buildString {
            appendLine("port ${server.port}")
            appendLine("proto $proto")
            appendLine("dev tun")
            appendLine("topology subnet")
            appendLine("server ${subnet.networkAddress()} ${subnet.netmask()}")
            appendLine("ca ca.crt")
            appendLine("cert server.crt")
            appendLine("key server.key")
            appendLine("dh none")
            appendLine("verify-client-cert none")
            appendLine("username-as-common-name")
            appendLine("script-security 2")
            appendLine("auth-user-pass-verify /etc/openvpn/server/ftth-verify.sh via-file")
            appendLine("client-connect /etc/openvpn/server/ftth-connect.sh")
            appendLine("client-disconnect /etc/openvpn/server/ftth-disconnect.sh")
            appendLine("keepalive 10 120")
            appendLine("persist-key")
            appendLine("persist-tun")
            // NCP: v7 nego AES-256-GCM; klien tanpa NCP (RouterOS v6, TCP) jatuh ke AES-256-CBC.
            appendLine("data-ciphers AES-256-GCM:AES-256-CBC")
            appendLine("data-ciphers-fallback AES-256-CBC")
            append("verb 3")
        }
    }

    /** Template installer dibaca sekali dari classpath (resource statis, aman di-cache). */
    private val installTemplate: String by lazy {
        val stream = javaClass.getResourceAsStream(INSTALL_TEMPLATE_PATH)
            ?: error("Template installer VPN tidak ditemukan di classpath: $INSTALL_TEMPLATE_PATH")
        stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private companion object {
        const val INSTALL_TEMPLATE_PATH = "/vpn/install.sh.template"

        /** Port Winbox default perangkat Mikrotik — tujuan DNAT dari port publik hub. */
        const val DEVICE_PORT = 8291
    }
}
