package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.vpn.application.service.VpnConfigRenderer
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji perakitan teks config OpenVPN — kelas biasa, diinstansiasi langsung. */
class VpnConfigRendererTest {

    private val renderer = VpnConfigRenderer()
    private val dummyCa = "-----BEGIN CERTIFICATE-----\nDUMMYCA\n-----END CERTIFICATE-----"

    private fun newServer(): VpnServer = VpnServer.create(
        name = "Hub Utama",
        host = "vpn.example.com",
        port = 1194,
        protocol = VpnProtocol.UDP,
        tunnelCidr = "10.8.0.0/24",
    )

    private fun newPeer(serverId: java.util.UUID): VpnPeer = VpnPeer.create(
        tenantId = UuidV7.generate(),
        serverId = serverId,
        name = "BRAS Jakarta 1",
        username = "bras-jakarta-1",
        overlayIp = "10.8.0.2",
        remotePort = 20000,
        password = "secretpassword12345",
        deviceType = null,
        deviceId = null,
    )

    @Test
    fun `ovpn memuat remote auth-user-pass username dan CA`() {
        val server = newServer().apply { setCredentials(dummyCa, null) }
        val peer = newPeer(server.id)

        val ovpn = renderer.renderOvpn(server, peer)

        assertThat(ovpn).contains("remote vpn.example.com 1194")
        assertThat(ovpn).contains("proto udp")
        assertThat(ovpn).contains("<auth-user-pass>")
        assertThat(ovpn).contains("bras-jakarta-1")
        assertThat(ovpn).contains("secretpassword12345")
        assertThat(ovpn).contains("<ca>")
        assertThat(ovpn).contains("DUMMYCA")
        assertThat(ovpn).doesNotContain("<tls-auth>")
    }

    @Test
    fun `ovpn menyertakan tls-auth hanya bila tlsAuthKey diisi`() {
        val server = newServer().apply { setCredentials(dummyCa, "TA-KEY-CONTENT") }
        val peer = newPeer(server.id)

        val ovpn = renderer.renderOvpn(server, peer)

        assertThat(ovpn).contains("key-direction 1")
        assertThat(ovpn).contains("<tls-auth>")
        assertThat(ovpn).contains("TA-KEY-CONTENT")
    }

    @Test
    fun `ovpn menolak bila CA belum di-set`() {
        val server = newServer()
        val peer = newPeer(server.id)

        assertThatThrownBy { renderer.renderOvpn(server, peer) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `routeros memuat connect-to user dan verify-server-certificate no`() {
        val server = newServer()
        val peer = newPeer(server.id)

        val script = renderer.renderRouterOs(server, peer)

        assertThat(script).contains("connect-to=vpn.example.com")
        assertThat(script).contains("port=1194")
        assertThat(script).contains("protocol=udp")
        assertThat(script).contains("user=\"bras-jakarta-1\"")
        assertThat(script).contains("verify-server-certificate=no")
    }

    @Test
    fun `server conf memuat server network netmask dan ccd per peer aktif`() {
        val server = newServer().apply { setCredentials(dummyCa, null) }
        val peer = newPeer(server.id)

        val config = renderer.renderServerConfig(server, listOf(peer))

        assertThat(config.serverConf).contains("server 10.8.0.0 255.255.255.0")
        assertThat(config.serverConf).contains("<ca>")
        assertThat(config.ccd).containsEntry("bras-jakarta-1", "ifconfig-push 10.8.0.2 255.255.255.0")
    }

    @Test
    fun `server conf mengganti blok CA dengan penanda bila CA belum di-set`() {
        val server = newServer()
        val peer = newPeer(server.id)

        val config = renderer.renderServerConfig(server, listOf(peer))

        assertThat(config.serverConf).contains("# CA belum di-set")
        assertThat(config.serverConf).doesNotContain("<ca>")
    }

    @Test
    fun `ccd hanya memuat peer ENABLED`() {
        val server = newServer().apply { setCredentials(dummyCa, null) }
        val enabled = newPeer(server.id)
        val disabled = VpnPeer.create(
            tenantId = UuidV7.generate(),
            serverId = server.id,
            name = "BRAS Bandung",
            username = "bras-bandung",
            overlayIp = "10.8.0.3",
            remotePort = 20001,
            password = "anotherpassword123",
            deviceType = null,
            deviceId = null,
        ).apply { disable() }

        val config = renderer.renderServerConfig(server, listOf(enabled, disabled))

        assertThat(config.ccd).containsKey("bras-jakarta-1")
        assertThat(config.ccd).doesNotContainKey("bras-bandung")
    }
}
