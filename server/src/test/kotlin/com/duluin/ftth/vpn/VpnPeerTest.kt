package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

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
    fun `create menghasilkan peer ENABLED dengan lastHandshake null dan offline`() {
        val peer = newPeer()
        assertThat(peer.status).isEqualTo(VpnPeerStatus.ENABLED)
        assertThat(peer.lastHandshakeAt).isNull()
        assertThat(peer.online).isFalse()
        assertThat(peer.username).isEqualTo("bras-jakarta-1")
    }

    @Test
    fun `markConnected menandai online dan mencatat waktu handshake`() {
        val peer = newPeer()
        val at = Instant.parse("2026-07-28T10:15:30Z")

        peer.markConnected(at)

        assertThat(peer.online).isTrue()
        assertThat(peer.lastHandshakeAt).isEqualTo(at)
    }

    @Test
    fun `markDisconnected menandai offline tapi mempertahankan jejak handshake terakhir`() {
        val peer = newPeer()
        val at = Instant.parse("2026-07-28T10:15:30Z")
        peer.markConnected(at)

        peer.markDisconnected()

        assertThat(peer.online).isFalse()
        // Jejak waktu terakhir online tetap disimpan sebagai "terakhir terhubung".
        assertThat(peer.lastHandshakeAt).isEqualTo(at)
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
