package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.VpnForwardProtocol
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerRoute
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import com.duluin.ftth.vpn.domain.model.VpnPortForward
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

    @Test
    fun `akun baru lahir dengan satu penerusan ke Winbox`() {
        val peer = newPeer()

        assertThat(peer.forwards).hasSize(1)
        val winbox = peer.forwards.single()
        assertThat(winbox.publicPort).isEqualTo(20000)
        assertThat(winbox.devicePort).isEqualTo(VpnPortForward.WINBOX_PORT)
        assertThat(winbox.label).isEqualTo("Winbox")
        assertThat(winbox.protocol).isEqualTo(VpnForwardProtocol.TCP)
        assertThat(peer.remotePort).isEqualTo(20000)
    }

    @Test
    fun `retarget mengubah port perangkat tanpa menggeser port publik`() {
        val peer = newPeer()
        val winbox = peer.forwards.single()

        // Skenario lapangan: teknisi memindah Winbox perangkat dari 8291 ke 9291.
        peer.retargetForward(winbox.id, label = null, devicePort = 9291, protocol = VpnForwardProtocol.TCP)

        assertThat(peer.forwards.single().devicePort).isEqualTo(9291)
        // Alamat yang sudah dipegang teknisi TAK BOLEH ikut berubah.
        assertThat(peer.remotePort).isEqualTo(20000)
        // Label ikut diturunkan dari port baru yang tak dikenal.
        assertThat(peer.forwards.single().label).isEqualTo("Port 9291")
    }

    @Test
    fun `penerusan tersusun urut port publik dan yang terendah jadi port utama`() {
        val peer = newPeer()
        peer.addForward(publicPort = 20005, label = null, devicePort = 22, protocol = VpnForwardProtocol.TCP)

        assertThat(peer.forwards.map { it.publicPort }).containsExactly(20000, 20005)
        assertThat(peer.forwards.last().label).isEqualTo("SSH")
        assertThat(peer.remotePort).isEqualTo(20000)
    }

    @Test
    fun `akun tanpa penerusan sah dan tak punya port utama`() {
        val peer = newPeer()

        peer.removeForward(peer.forwards.single().id)

        // Sengaja tak diekspos ke internet: perangkat hanya terjangkau dari dalam tunnel.
        assertThat(peer.forwards).isEmpty()
        assertThat(peer.remotePort).isNull()
    }

    @Test
    fun `menambah penerusan melebihi batas ditolak`() {
        val peer = newPeer()
        repeat(VpnPortForward.MAX_PER_PEER - 1) {
            peer.addForward(20001 + it, label = null, devicePort = 1000 + it, protocol = VpnForwardProtocol.TCP)
        }

        assertThatThrownBy {
            peer.addForward(30000, label = null, devicePort = 8728, protocol = VpnForwardProtocol.TCP)
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `mengubah penerusan yang bukan miliknya ditolak`() {
        val peer = newPeer()

        assertThatThrownBy {
            peer.retargetForward(UuidV7.generate(), null, 22, VpnForwardProtocol.TCP)
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `akun baru lahir tanpa blok di belakangnya`() {
        // Menebak kolam pelanggan saat generate berarti memasang rute yang salah pada mayoritas
        // akun — yang cuma dipakai remote Winbox.
        assertThat(newPeer().routes).isEmpty()
    }

    @Test
    fun `addRoute menormalisasi alamat pelanggan dan memberi nama default`() {
        val peer = newPeer()

        val route = peer.addRoute("10.20.255.254/16", label = null)

        assertThat(route.cidr).isEqualTo("10.20.0.0/16")
        assertThat(route.label).isEqualTo("Blok pelanggan")
        assertThat(peer.routes).containsExactly(route)
    }

    @Test
    fun `blok tersusun urut CIDR`() {
        val peer = newPeer()
        peer.addRoute("10.30.0.0/16", "Hotspot")
        peer.addRoute("10.20.0.0/16", "PPPoE pelanggan")

        assertThat(peer.routes.map { it.cidr }).containsExactly("10.20.0.0/16", "10.30.0.0/16")
    }

    @Test
    fun `menambah blok yang beririsan di akun yang sama ditolak`() {
        val peer = newPeer()
        peer.addRoute("10.20.0.0/16", "PPPoE pelanggan")

        // OpenVPN tak mengeluh atas dua pemilik blok yang sama — ia diam-diam memilih salah satu.
        assertThatThrownBy { peer.addRoute("10.20.5.0/24", "Sebagian") }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `menambah blok melebihi batas ditolak`() {
        val peer = newPeer()
        repeat(VpnPeerRoute.MAX_PER_PEER) { peer.addRoute("10.${20 + it}.0.0/16", null) }

        assertThatThrownBy { peer.addRoute("10.99.0.0/16", null) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `renameRoute mengganti nama tanpa menyentuh bloknya`() {
        val peer = newPeer()
        val route = peer.addRoute("10.20.0.0/16", "PPPoE pelanggan")

        peer.renameRoute(route.id, "Kolam Cianjur")

        assertThat(peer.routes.single().label).isEqualTo("Kolam Cianjur")
        assertThat(peer.routes.single().cidr).isEqualTo("10.20.0.0/16")
    }

    @Test
    fun `removeRoute membebaskan bloknya untuk didaftarkan ulang`() {
        val peer = newPeer()
        val route = peer.addRoute("10.20.0.0/16", null)

        peer.removeRoute(route.id)

        assertThat(peer.routes).isEmpty()
        // Setelah dicabut, blok yang sama (atau irisannya) boleh masuk lagi.
        assertThat(peer.addRoute("10.20.5.0/24", null).cidr).isEqualTo("10.20.5.0/24")
    }

    @Test
    fun `mengubah blok yang bukan miliknya ditolak`() {
        val peer = newPeer()

        assertThatThrownBy { peer.renameRoute(UuidV7.generate(), "apa saja") }
            .isInstanceOf(NotFoundException::class.java)
    }
}
