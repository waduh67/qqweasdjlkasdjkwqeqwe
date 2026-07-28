package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Pembacaan hub/peer untuk provisioning. Hub & akun kini tabel tanpa RLS, jadi peer di-resolve
 * lintas-tenant lewat (serverId, username) — persis yang dibutuhkan callback OpenVPN yang tak
 * tahu tenant. `@Transactional(readOnly)` sekadar membungkus tiap pembacaan dalam satu sesi;
 * pencatatan liveness ([recordConnect]/[recordDisconnect]) memakai transaksi tulis biasa.
 */
@Component
class VpnProvisioningReader(
    private val serverRepository: VpnServerRepository,
    private val peerRepository: VpnPeerRepository,
    private val renderer: VpnConfigRenderer,
) {
    @Transactional(readOnly = true)
    fun renderInstaller(serverId: UUID, rawToken: String, appBaseUrl: String): String {
        val server = serverRepository.findById(serverId)
            ?: throw NotFoundException("Hub VPN $serverId tidak ditemukan")
        return renderer.renderInstallScript(server, appBaseUrl, rawToken)
    }

    /** True hanya bila peer ada, AKTIF, dan passwordnya cocok (banding waktu-tetap). */
    @Transactional(readOnly = true)
    fun verifyCredentials(serverId: UUID, username: String, password: String): Boolean {
        val peer = enabledPeer(serverId, username) ?: return false
        return constantTimeEquals(peer.password, password)
    }

    /**
     * Data koneksi peer aktif: `{overlayIp} {netmask} {remotePort}` (dipisah spasi). Skrip
     * `client-connect` di VPS memakainya untuk menulis `ifconfig-push {overlayIp} {netmask}` ke
     * berkas CCD SEKALIGUS memasang DNAT port publik → Winbox perangkat. Null bila tak ada/nonaktif.
     */
    @Transactional(readOnly = true)
    fun clientConnectLine(serverId: UUID, username: String): String? {
        val peer = enabledPeer(serverId, username) ?: return null
        val server = serverRepository.findById(serverId)
            ?: throw ConflictException("Hub VPN $serverId hilang saat client-connect")
        val netmask = TunnelSubnet.parse(server.tunnelCidr).netmask()
        return "${peer.overlayIp} $netmask ${peer.remotePort}"
    }

    /**
     * Hub melaporkan peer BARU TERHUBUNG (`client-connect`): tandai online + catat waktu. Telemetri
     * murni — tak menggerbang tunnel; balikannya sekadar penanda apakah peernya dikenal. Peer dicari
     * tanpa filter status: bila mulai putus setelah dinonaktifkan, laporan tetap tercermin jujur.
     */
    @Transactional
    fun recordConnect(serverId: UUID, username: String): Boolean {
        val peer = peerRepository.findByServerIdAndUsername(serverId, username) ?: return false
        peer.markConnected(Instant.now())
        peerRepository.save(peer)
        return true
    }

    /** Hub melaporkan peer PUTUS (`client-disconnect`): tandai offline. Lihat [recordConnect]. */
    @Transactional
    fun recordDisconnect(serverId: UUID, username: String): Boolean {
        val peer = peerRepository.findByServerIdAndUsername(serverId, username) ?: return false
        peer.markDisconnected()
        peerRepository.save(peer)
        return true
    }

    private fun enabledPeer(serverId: UUID, username: String): VpnPeer? =
        peerRepository.findByServerIdAndUsername(serverId, username)
            ?.takeIf { it.status == VpnPeerStatus.ENABLED }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
