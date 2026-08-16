package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.application.service.VpnConfigRenderer
import com.duluin.ftth.vpn.application.service.VpnProvisioningReader
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji apa yang DIKIRIM ke VPS soal blok di belakang peer — dua jalur yang harus sejalan:
 * `iroute` di balasan client-connect (tabel internal OpenVPN) dan tabel rute yang ditarik
 * penyelaras berkala (rute kernel + NAT). Fake repository, tanpa Spring maupun database.
 */
class VpnProvisioningRouteTest {

    private val server = VpnServer.create(
        name = "Hub Uji",
        host = "vpn.example.com",
        port = 1194,
        protocol = VpnProtocol.UDP,
        tunnelCidr = "10.8.0.0/24",
    )

    private fun newPeer(username: String, overlayIp: String): VpnPeer = VpnPeer.create(
        tenantId = UuidV7.generate(),
        serverId = server.id,
        name = "BRAS $username",
        username = username,
        overlayIp = overlayIp,
        remotePort = 20000,
        password = "initialpassword123",
        deviceType = null,
        deviceId = null,
    )

    private fun readerOf(vararg peers: VpnPeer) =
        VpnProvisioningReader(FixedServerRepository(server), FakePeerRepository(peers.toList()), VpnConfigRenderer())

    @Test
    fun `client-connect tanpa blok tetap satu baris seperti dulu`() {
        val peer = newPeer("bras-satu", "10.8.0.2")

        val body = readerOf(peer).clientConnectLine(server.id, "bras-satu")

        // Hub lama di lapangan mem-parse ini dengan `read -r ip mask port` — bentuknya tak bergeser.
        assertThat(body).isEqualTo("10.8.0.2 255.255.255.0 20000")
    }

    @Test
    fun `client-connect menambahkan baris iroute per blok, tanpa mengubah baris pertama`() {
        val peer = newPeer("bras-satu", "10.8.0.2").apply {
            addRoute("10.20.0.0/16", "Kolam PPPoE")
            addRoute("10.30.5.7/32", "ONT uji")
        }

        val lines = readerOf(peer).clientConnectLine(server.id, "bras-satu")!!.lines()

        // Baris pertama tetap tiga kolom: hub lama membacanya persis seperti sebelumnya dan
        // sekadar mengabaikan sisanya sampai installernya dijalankan ulang.
        assertThat(lines.first()).isEqualTo("10.8.0.2 255.255.255.0 20000")
        assertThat(lines.drop(1)).containsExactly(
            "iroute 10.20.0.0 255.255.0.0",
            "iroute 10.30.5.7 255.255.255.255",
        )
    }

    @Test
    fun `tabel rute berpenanda, memuat blok semua peer aktif, terurut`() {
        val satu = newPeer("bras-satu", "10.8.0.2").apply { addRoute("10.30.0.0/16", null) }
        val dua = newPeer("bras-dua", "10.8.0.3").apply { addRoute("10.20.0.0/16", null) }

        val table = readerOf(satu, dua).routeTable(server.id)

        assertThat(table).isEqualTo("#ftth-routes\n10.20.0.0/16\n10.30.0.0/16\n")
    }

    @Test
    fun `blok milik peer nonaktif tak masuk tabel rute`() {
        val aktif = newPeer("bras-satu", "10.8.0.2").apply { addRoute("10.20.0.0/16", null) }
        val mati = newPeer("bras-dua", "10.8.0.3").apply {
            addRoute("10.30.0.0/16", null)
            disable()
        }

        val table = readerOf(aktif, mati).routeTable(server.id)

        // Menonaktifkan akun harus ikut mencabut jalan ke blok di belakangnya — tanpa itu,
        // "menonaktifkan" cuma menutup pintu depan sementara pintu belakang tetap terbuka.
        assertThat(table.lines()).containsExactly("#ftth-routes", "10.20.0.0/16", "")
        assertThat(readerOf(aktif, mati).clientConnectLine(server.id, "bras-dua")).isNull()
    }

    @Test
    fun `hub tanpa blok tetap mengirim penanda`() {
        // Penting: penanda yang hilang berarti "aplikasi bermasalah, jangan sentuh apa pun" bagi
        // VPS. Hub yang memang belum punya blok harus mengirim daftar KOSONG yang sah, supaya
        // rute sisa blok yang baru dihapus operator benar-benar tercabut.
        assertThat(readerOf(newPeer("bras-satu", "10.8.0.2")).routeTable(server.id)).isEqualTo("#ftth-routes\n")
    }

    /** Fake peer repo: jalur provisioning hanya membaca peer per hub / per username. */
    private class FakePeerRepository(private val peers: List<VpnPeer>) : VpnPeerRepository {
        override fun findByServerId(serverId: UUID): List<VpnPeer> = peers.filter { it.serverId == serverId }
        override fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeer? =
            peers.firstOrNull { it.serverId == serverId && it.username == username }

        override fun findById(id: UUID): VpnPeer? = throw NotImplementedError()
        override fun findByTenant(tenantId: UUID): List<VpnPeer> = throw NotImplementedError()
        override fun save(peer: VpnPeer): VpnPeer = throw NotImplementedError()
        override fun usedOverlayIps(serverId: UUID): Set<String> = throw NotImplementedError()
        override fun usedRemotePorts(serverId: UUID): Set<Int> = throw NotImplementedError()
        override fun routedCidrsByServerIdExcluding(serverId: UUID, peerId: UUID): List<String> =
            throw NotImplementedError()
        override fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean = throw NotImplementedError()
        override fun countByServerId(serverId: UUID): Long = throw NotImplementedError()
        override fun deleteById(id: UUID) = throw NotImplementedError()
    }

    /** Hub tunggal; netmask tunnel-nya dipakai baris pertama client-connect. */
    private class FixedServerRepository(private val server: VpnServer) : VpnServerRepository {
        override fun findById(id: UUID): VpnServer? = server.takeIf { it.id == id }
        override fun findAll(): List<VpnServer> = listOf(server)
        override fun findAssignable(): List<VpnServer> = listOf(server)
        override fun save(server: VpnServer): VpnServer = throw NotImplementedError()
        override fun delete(id: UUID) = throw NotImplementedError()
    }
}
