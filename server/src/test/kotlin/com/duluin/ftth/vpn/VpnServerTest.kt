package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import com.duluin.ftth.vpn.domain.model.VpnServerStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji validasi & mutasi hub VPN — murni domain. */
class VpnServerTest {

    private fun newServer(port: Int = 1194, tunnelCidr: String = "10.8.0.0/24"): VpnServer = VpnServer.create(
        tenantId = UuidV7.generate(),
        name = "Hub Utama",
        host = "vpn.example.com",
        port = port,
        protocol = VpnProtocol.UDP,
        tunnelCidr = tunnelCidr,
    )

    @Test
    fun `create menghasilkan hub ACTIVE tanpa kredensial`() {
        val server = newServer()
        assertThat(server.status).isEqualTo(VpnServerStatus.ACTIVE)
        assertThat(server.caCertPem).isNull()
        assertThat(server.tlsAuthKey).isNull()
    }

    @Test
    fun `create menolak port di luar rentang`() {
        assertThatThrownBy { newServer(port = 0) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { newServer(port = 70_000) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `create menolak tunnelCidr tak valid`() {
        assertThatThrownBy { newServer(tunnelCidr = "bukan-cidr") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { newServer(tunnelCidr = "10.8.0.0/33") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `setCredentials mengisi CA dan tls-auth`() {
        val server = newServer()

        server.setCredentials("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----", "ta-key-content")

        assertThat(server.caCertPem).contains("BEGIN CERTIFICATE")
        assertThat(server.tlsAuthKey).isEqualTo("ta-key-content")
    }

    @Test
    fun `setCredentials memperlakukan blank sebagai null`() {
        val server = newServer()
        server.setCredentials("cert", "key")

        server.setCredentials("   ", null)

        assertThat(server.caCertPem).isNull()
        assertThat(server.tlsAuthKey).isNull()
    }

    @Test
    fun `disable lalu enable memindahkan status`() {
        val server = newServer()

        server.disable()
        assertThat(server.status).isEqualTo(VpnServerStatus.DISABLED)

        server.enable()
        assertThat(server.status).isEqualTo(VpnServerStatus.ACTIVE)
    }
}
