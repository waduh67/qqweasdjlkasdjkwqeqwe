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
     * Data koneksi peer aktif. BARIS PERTAMA: `{overlayIp} {netmask} {portPublikUtama}` (dipisah
     * spasi) — dipakai skrip `client-connect` di VPS untuk menulis `ifconfig-push` ke berkas
     * config sesi. BARIS BERIKUTNYA: satu `iroute {network} {netmask}` per blok di belakang peer,
     * yang memberi tahu OpenVPN bahwa alamat-alamat itu tinggal di balik tunnel peer ini.
     * Null bila peernya tak ada/nonaktif.
     *
     * Kolomnya TAK BOLEH bertambah: hub yang sudah terpasang di lapangan mem-parse balasan ini
     * dengan `read -r ip mask port`, yang menyerap kolom lebih dari tiga ke variabel terakhir.
     * Menambah BARIS aman — `read` hanya membaca baris pertama, jadi hub lama sekadar mengabaikan
     * blok (perilaku lamanya) sampai installernya dijalankan ulang.
     *
     * `iroute` saja belum cukup: ia hanya tabel internal OpenVPN. Rute kernel yang menyeret
     * paketnya ke perangkat tun dipasang penyelaras berkala — lihat [routeTable].
     */
    @Transactional(readOnly = true)
    fun clientConnectLine(serverId: UUID, username: String): String? {
        val peer = enabledPeer(serverId, username) ?: return null
        val server = serverRepository.findById(serverId)
            ?: throw ConflictException("Hub VPN $serverId hilang saat client-connect")
        val netmask = TunnelSubnet.parse(server.tunnelCidr).netmask()
        val head = "${peer.overlayIp} $netmask ${peer.remotePort ?: ""}".trimEnd()
        val iroutes = peer.routes.map { "iroute ${it.subnet.networkAddress()} ${it.subnet.netmask()}" }
        return (listOf(head) + iroutes).joinToString("\n")
    }

    /**
     * Blok alamat SELURUH hub yang harus punya rute kernel ke perangkat tun, satu CIDR per baris,
     * didahului penanda [ROUTE_MARKER]. Ditarik berkala oleh `ftth-sync.sh` bersama [forwardTable].
     *
     * Kenapa dipisah dari `iroute` di [clientConnectLine]: keduanya menjawab pertanyaan berbeda.
     * `iroute` menjawab "peer mana pemilik blok ini" (di dalam OpenVPN), rute kernel menjawab
     * "paket ke blok ini harus lewat mana" (di luar OpenVPN, tempat GenieACS/aplikasi di VPS
     * mengirim paketnya). Hilang salah satu = lubang hitam tanpa satu baris log pun.
     *
     * Ditarik berkala, bukan dipasang saat connect, dengan alasan yang sama seperti [forwardTable]:
     * operator mendaftarkan blok pukul 10 pagi tanpa memutus tunnel, dan rute kernel ikut lenyap
     * saat VPS reboot. Hanya peer ENABLED yang masuk → menonaktifkan akun ikut menutup jalannya.
     */
    @Transactional(readOnly = true)
    fun routeTable(serverId: UUID): String {
        val cidrs = peerRepository.findByServerId(serverId)
            .filter { it.status == VpnPeerStatus.ENABLED }
            .flatMap { peer -> peer.routes.map { it.cidr } }
            .sorted()
        return (listOf(ROUTE_MARKER) + cidrs).joinToString("\n", postfix = "\n")
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

        /** Penanda baris pertama [routeTable] — kontrak dengan `ftth-sync.sh` di VPS. */
        const val ROUTE_MARKER = "#ftth-routes"
    }
}
