package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji validasi & transisi peer VPN — murni domain. */
class VpnPeerTest {

    private fun newPeer(username: String = "bras-jakarta-1"): VpnPeer = VpnPeer.create(
        tenantId = UuidV7.generate(),
        serverId = UuidV7.generate(),
        name = "BRAS Jakarta 1",
        username = username,
        overlayIp = "10.8.0.2",
        remotePort = 20000,
        password = "initialpassword123",
        deviceType = null,
        deviceId = null,
    )

    @Test
    fun `create menolak port remote di luar 1-65535`() {
        assertThatThrownBy {
            VpnPeer.create(
                tenantId = UuidV7.generate(),
                serverId = UuidV7.generate(),
                name = "BRAS Jakarta 1",
                username = "bras-jakarta-1",
                overlayIp = "10.8.0.2",
                remotePort = 70000,
                password = "initialpassword123",
                deviceType = null,
                deviceId = null,
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `create menghasilkan peer ENABLED dengan lastHandshake null`() {
        val peer = newPeer()
        assertThat(peer.status).isEqualTo(VpnPeerStatus.ENABLED)
        assertThat(peer.lastHandshakeAt).isNull()
        assertThat(peer.username).isEqualTo("bras-jakarta-1")
    }

    @Test
    fun `create menolak username dengan karakter ilegal`() {
        assertThatThrownBy { newPeer(username = "bad name!") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `disable lalu enable memindahkan status`() {
        val peer = newPeer()

        peer.disable()
        assertThat(peer.status).isEqualTo(VpnPeerStatus.DISABLED)

        peer.enable()
        assertThat(peer.status).isEqualTo(VpnPeerStatus.ENABLED)
    }

    @Test
    fun `rotatePassword mengganti password`() {
        val peer = newPeer()

        peer.rotatePassword("newrotatedpassword")

        assertThat(peer.password).isEqualTo("newrotatedpassword")
    }

    @Test
    fun `rotatePassword menolak kosong`() {
        val peer = newPeer()
        assertThatThrownBy { peer.rotatePassword("  ") }
            .isInstanceOf(ValidationException::class.java)
    }
}
