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
     * Data koneksi peer aktif: `{overlayIp} {netmask} {portPublikUtama}` (dipisah spasi). Skrip
     * `client-connect` di VPS memakainya untuk menulis `ifconfig-push {overlayIp} {netmask}` ke
     * berkas CCD. Null bila tak ada/nonaktif.
     *
     * Bentuknya SATU BARIS dan tetap begitu: hub yang sudah terpasang di lapangan mem-parse-nya
     * dengan `read -r ip mask port`, jadi menambah kolom/baris di sini akan menabrak mereka.
     * Penerusan port dipisah ke [forwardTable] yang disinkronkan berkala.
     */
    @Transactional(readOnly = true)
    fun clientConnectLine(serverId: UUID, username: String): String? {
        val peer = enabledPeer(serverId, username) ?: return null
        val server = serverRepository.findById(serverId)
            ?: throw ConflictException("Hub VPN $serverId hilang saat client-connect")
        val netmask = TunnelSubnet.parse(server.tunnelCidr).netmask()
        return "${peer.overlayIp} $netmask ${peer.remotePort ?: ""}".trimEnd()
    }

    /**
     * Tabel penerusan port SELURUH hub, dibaca berkala oleh `ftth-sync.sh` di VPS untuk
     * merekonsiliasi aturan DNAT-nya: `{portPublik} {ipOverlay} {portPerangkat} {protokol}`,
     * satu baris per penerusan, didahului penanda [FORWARD_MARKER].
     *
     * Kenapa disinkronkan berkala, bukan dipasang saat `client-connect`:
     *  - operator memindah port Winbox perangkat pukul 10 pagi; kalau aturannya hanya dipasang
     *    saat menyambung, perubahan itu baru berlaku ketika tunnelnya kebetulan putus-sambung.
     *  - aturan iptables hilang saat VPS reboot; sinkronisasi berkala memulihkannya sendiri.
     *  - hanya peer ENABLED yang masuk daftar → menonaktifkan akun ikut menutup pintunya.
     *
     * Penanda di baris pertama itu penting: bila aplikasi mati atau membalas halaman error,
     * skrip di VPS harus BERHENTI tanpa menyentuh iptables, bukan menyapu semua aturannya.
     */
    @Transactional(readOnly = true)
    fun forwardTable(serverId: UUID): String {
        val rules = peerRepository.findByServerId(serverId)
            .filter { it.status == VpnPeerStatus.ENABLED }
            .flatMap { peer ->
                peer.forwards.map { "${it.publicPort} ${peer.overlayIp} ${it.devicePort} ${it.protocol.name.lowercase()}" }
            }
        return (listOf(FORWARD_MARKER) + rules).joinToString("\n", postfix = "\n")
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

    companion object {
        /** Penanda baris pertama [forwardTable] — kontrak dengan `ftth-sync.sh` di VPS. */
        const val FORWARD_MARKER = "#ftth-forwards"
    }
}
