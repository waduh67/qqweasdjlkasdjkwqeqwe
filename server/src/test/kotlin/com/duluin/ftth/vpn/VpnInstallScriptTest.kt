package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.vpn.application.service.VpnConfigRenderer
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji perakitan installer satu-perintah dari template classpath — murni, tanpa I/O jaringan. */
class VpnInstallScriptTest {

    private val renderer = VpnConfigRenderer()

    private fun readyServer(): VpnServer = VpnServer.create(
        name = "Hub Utama",
        host = "vpn.example.com",
        port = 1194,
        protocol = VpnProtocol.UDP,
        tunnelCidr = "10.8.0.0/24",
    ).apply {
        attachPki(
            caCertPem = "-----BEGIN CERTIFICATE-----\nCA-CONTENT\n-----END CERTIFICATE-----",
            caKeyPem = "-----BEGIN PRIVATE KEY-----\nCA-KEY\n-----END PRIVATE KEY-----",
            serverCertPem = "-----BEGIN CERTIFICATE-----\nSERVER-CONTENT\n-----END CERTIFICATE-----",
            serverKeyPem = "-----BEGIN PRIVATE KEY-----\nSERVER-KEY\n-----END PRIVATE KEY-----",
        )
    }

    @Test
    fun `installer menanam token url sertifikat dan config callback`() {
        val script = renderer.renderInstallScript(readyServer(), "https://app.example.com/", "ftthv_TOKEN123")

        // Semua placeholder tergantikan.
        assertThat(script).doesNotContain("{{").doesNotContain("}}")
        // Token & URL (trailing slash dipangkas) ter-embed untuk callback.
        assertThat(script).contains("NODE_TOKEN=\"ftthv_TOKEN123\"")
        assertThat(script).contains("APP_URL=\"https://app.example.com\"")
        assertThat(script).contains("/api/vpn/provision/authenticate")
        assertThat(script).contains("/api/vpn/provision/client-connect")
        // Sertifikat/kunci server ditulis dari PKI aplikasi.
        assertThat(script).contains("CA-CONTENT")
        assertThat(script).contains("SERVER-CONTENT")
        assertThat(script).contains("SERVER-KEY")
        // server.conf model callback-langsung.
        assertThat(script).contains("server 10.8.0.0 255.255.255.0")
        assertThat(script).contains("verify-client-cert none")
        assertThat(script).contains("username-as-common-name")
        assertThat(script).contains("auth-user-pass-verify /etc/openvpn/server/ftth-verify.sh via-file")
        assertThat(script).contains("client-connect /etc/openvpn/server/ftth-connect.sh")
        assertThat(script).contains("client-disconnect /etc/openvpn/server/ftth-disconnect.sh")
        assertThat(script).contains("dh none")
        // DNAT port publik -> Winbox: rute-balik MASQUERADE + FORWARD subnet tunnel, port device 8291.
        assertThat(script).contains("TUN_CIDR=\"10.8.0.0/24\"")
        assertThat(script).contains("-j MASQUERADE")
        assertThat(script).contains("--to-destination \"\$ip:8291\"")
        assertThat(script).contains("ftth-disconnect.sh")
    }

    @Test
    fun `installer menolak hub yang PKI-nya belum siap`() {
        val notReady = VpnServer.create(
            name = "Hub Baru",
            host = "vpn2.example.com",
            port = 1194,
            protocol = VpnProtocol.UDP,
            tunnelCidr = "10.9.0.0/24",
        )

        assertThatThrownBy { renderer.renderInstallScript(notReady, "https://app.example.com", "ftthv_X") }
            .isInstanceOf(ConflictException::class.java)
    }
}
