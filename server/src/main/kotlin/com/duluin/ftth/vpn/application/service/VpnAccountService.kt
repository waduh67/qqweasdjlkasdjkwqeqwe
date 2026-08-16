package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.vpn.application.port.inbound.GenerateVpnAccountCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnAccountUseCase
import com.duluin.ftth.vpn.application.port.inbound.VpnAccountView
import com.duluin.ftth.vpn.application.port.inbound.VpnPortForwardCommand
import com.duluin.ftth.vpn.application.port.inbound.VpnPortForwardView
import com.duluin.ftth.vpn.application.port.inbound.VpnRouteCommand
import com.duluin.ftth.vpn.application.port.inbound.VpnRouteLabelCommand
import com.duluin.ftth.vpn.application.port.inbound.VpnRoutedSubnetView
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.config.VpnProperties
import com.duluin.ftth.vpn.domain.model.RemotePortRange
import com.duluin.ftth.vpn.domain.model.RoutedSubnet
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnClientVariant
import com.duluin.ftth.vpn.domain.model.VpnForwardProtocol
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Layanan AKUN VPN menghadap tenant. Tenant tak pernah memilih hub: [generate] meng-AUTO-ASSIGN
 * ke hub platform yang paling lengang, mengalokasikan IP overlay & username unik LINTAS-TENANT
 * per hub (satu hub dibagi banyak tenant), lalu mengembalikan kredensial siap tempel di Mikrotik.
 *
 * Isolasi tenant ditegakkan di aplikasi (tabel akun tanpa RLS, cermin collector): setiap operasi
 * memverifikasi kepemilikan lewat [ownedPeer] sebelum menyentuh akun.
 */
@Service
@Transactional
class VpnAccountService(
    private val serverRepository: VpnServerRepository,
    private val peerRepository: VpnPeerRepository,
    private val renderer: VpnConfigRenderer,
    private val passwordGenerator: PasswordGenerator,
    private val properties: VpnProperties,
    private val auditor: AuditRecorder,
) : ManageVpnAccountUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<VpnAccountView> {
        val serverCache = HashMap<UUID, VpnServer>()
        return peerRepository.findByTenant(TenantContext.tenantId())
            .map { it.toView(serverFor(it.serverId, serverCache)) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): VpnAccountView {
        val peer = ownedPeer(id)
        return peer.toView(requireServer(peer.serverId))
    }

    override fun generate(command: GenerateVpnAccountCommand): VpnAccountView {
        val server = pickServer()
        val label = command.label?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL
        val overlayIp = TunnelSubnet.parse(server.tunnelCidr).allocate(peerRepository.usedOverlayIps(server.id))
        val remotePort = RemotePortRange(properties.remotePortMin, properties.remotePortMax)
            .allocate(peerRepository.usedRemotePorts(server.id))
        val username = resolveUsername(server.id, label, command.username)
        val peer = VpnPeer.create(
            tenantId = TenantContext.tenantId(),
            serverId = server.id,
            name = label,
            username = username,
            overlayIp = overlayIp,
            remotePort = remotePort,
            password = passwordGenerator.generate(),
            deviceType = command.deviceType,
            deviceId = command.deviceId,
        )
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.generated", "VpnPeer", saved.id, saved.tenantId,
            mapOf(
                "username" to saved.username, "overlayIp" to saved.overlayIp,
                "remotePort" to saved.remotePort, "serverId" to server.id,
            ),
        )
        // Password disertakan SEKALI di sini (sekali tampil).
        return saved.toView(server, revealPassword = true)
    }

    override fun enable(id: UUID): VpnAccountView {
        val peer = ownedPeer(id)
        peer.enable()
        val saved = peerRepository.save(peer)
        auditor.record("vpn.account.enabled", "VpnPeer", saved.id, saved.tenantId, mapOf("username" to saved.username))
        return saved.toView(requireServer(saved.serverId))
    }

    override fun disable(id: UUID): VpnAccountView {
        val peer = ownedPeer(id)
        peer.disable()
        val saved = peerRepository.save(peer)
        auditor.record("vpn.account.disabled", "VpnPeer", saved.id, saved.tenantId, mapOf("username" to saved.username))
        return saved.toView(requireServer(saved.serverId))
    }

    override fun rotatePassword(id: UUID): VpnAccountView {
        val peer = ownedPeer(id)
        peer.rotatePassword(passwordGenerator.generate())
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.password-rotated", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username),
        )
        return saved.toView(requireServer(saved.serverId), revealPassword = true)
    }

    override fun delete(id: UUID) {
        val peer = ownedPeer(id)
        peerRepository.deleteById(id)
        auditor.record("vpn.account.deleted", "VpnPeer", id, peer.tenantId, mapOf("username" to peer.username))
    }

    override fun addForward(id: UUID, command: VpnPortForwardCommand): VpnAccountView {
        val peer = ownedPeer(id)
        val server = requireServer(peer.serverId)
        // Port publik dialokasikan sistem dari kolam hub — SEMUA penerusan ikut dihitung,
        // bukan satu per akun, sebab satu perangkat kini boleh punya beberapa pintu.
        val publicPort = RemotePortRange(properties.remotePortMin, properties.remotePortMax)
            .allocate(peerRepository.usedRemotePorts(server.id))
        val forward = peer.addForward(
            publicPort = publicPort,
            label = command.label,
            devicePort = command.devicePort,
            protocol = command.protocol ?: VpnForwardProtocol.TCP,
        )
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.forward-added", "VpnPeer", saved.id, saved.tenantId,
            mapOf(
                "username" to saved.username, "publicPort" to forward.publicPort,
                "devicePort" to forward.devicePort, "protocol" to forward.protocol.name,
            ),
        )
        return saved.toView(server)
    }

    override fun retargetForward(id: UUID, forwardId: UUID, command: VpnPortForwardCommand): VpnAccountView {
        val peer = ownedPeer(id)
        peer.retargetForward(
            forwardId = forwardId,
            label = command.label,
            devicePort = command.devicePort,
            protocol = command.protocol ?: VpnForwardProtocol.TCP,
        )
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.forward-retargeted", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "forwardId" to forwardId, "devicePort" to command.devicePort),
        )
        return saved.toView(requireServer(saved.serverId))
    }

    override fun removeForward(id: UUID, forwardId: UUID): VpnAccountView {
        val peer = ownedPeer(id)
        peer.removeForward(forwardId)
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.forward-removed", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "forwardId" to forwardId),
        )
        return saved.toView(requireServer(saved.serverId))
    }

    override fun addRoute(id: UUID, command: VpnRouteCommand): VpnAccountView {
        val peer = ownedPeer(id)
        val server = requireServer(peer.serverId)
        val subnet = RoutedSubnet.parse(command.cidr)
        guardTunnelCidr(server, subnet)
        guardOtherPeers(server.id, peer.id, subnet)
        // Irisan DI DALAM akun ini dijaga agregatnya sendiri.
        val route = peer.addRoute(subnet.cidr, command.label)
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.route-added", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "cidr" to route.cidr, "serverId" to server.id),
        )
        return saved.toView(server)
    }

    override fun renameRoute(id: UUID, routeId: UUID, command: VpnRouteLabelCommand): VpnAccountView {
        val peer = ownedPeer(id)
        peer.renameRoute(routeId, command.label)
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.route-renamed", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "routeId" to routeId),
        )
        return saved.toView(requireServer(saved.serverId))
    }

    override fun removeRoute(id: UUID, routeId: UUID): VpnAccountView {
        val peer = ownedPeer(id)
        val cidr = peer.routes.firstOrNull { it.id == routeId }?.cidr
        peer.removeRoute(routeId)
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.account.route-removed", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "routeId" to routeId, "cidr" to cidr),
        )
        return saved.toView(requireServer(saved.serverId))
    }

    /**
     * Blok pelanggan tak boleh menyentuh subnet tunnel hub. Bila dibiarkan, rute yang dipasang
     * akan menimpa jalan pulang tunnel itu sendiri — seluruh peer di hub putus sekaligus, dan
     * gejalanya tak menunjuk ke blok yang baru saja didaftarkan.
     */
    private fun guardTunnelCidr(server: VpnServer, subnet: RoutedSubnet) {
        val tunnel = RoutedSubnet.parse(TunnelSubnet.parse(server.tunnelCidr).cidr)
        if (subnet.overlaps(tunnel)) {
            throw ConflictException("Blok ${subnet.cidr} beririsan dengan alamat tunnel VPN (${tunnel.cidr})")
        }
    }

    /**
     * Blok yang beririsan dengan milik akun LAIN di hub yang sama ditolak — termasuk akun tenant
     * lain. Satu hub punya satu tabel rute; OpenVPN tak mengeluh atas dua pemilik blok yang sama,
     * ia hanya diam-diam memilih salah satu. Pesan galat sengaja tak menyebut akun/tenant
     * pemiliknya: cukup fakta bahwa bloknya sudah dipakai.
     */
    private fun guardOtherPeers(serverId: UUID, peerId: UUID, subnet: RoutedSubnet) {
        peerRepository.routedCidrsByServerIdExcluding(serverId, peerId)
            .map(RoutedSubnet::parse)
            .firstOrNull { it.overlaps(subnet) }
            ?.let { throw ConflictException("Blok ${subnet.cidr} beririsan dengan ${it.cidr} yang sudah dipakai akun lain di server VPN ini") }
    }

    @Transactional(readOnly = true)
    override fun renderOvpn(id: UUID, variant: VpnClientVariant): String {
        val peer = ownedPeer(id)
        return renderer.renderOvpn(requireServer(peer.serverId), peer, variant)
    }

    @Transactional(readOnly = true)
    override fun renderRouterOs(id: UUID, variant: VpnClientVariant): String {
        val peer = ownedPeer(id)
        val server = requireServer(peer.serverId)
        return when (variant) {
            VpnClientVariant.V7 -> renderer.renderRouterOs(server, peer)
            VpnClientVariant.V6 -> renderer.renderRouterOsV6(server, peer)
        }
    }

    /** Hub paling lengang di antara yang siap-pakai (ACTIVE + PKI); menolak bila belum ada. */
    private fun pickServer(): VpnServer =
        serverRepository.findAssignable().minByOrNull { peerRepository.countByServerId(it.id) }
            ?: throw ConflictException("Belum ada server VPN yang tersedia. Hubungi admin platform.")

    /**
     * Username eksplisit dipakai apa adanya; bila kosong, diturunkan dari label (huruf-kecil,
     * non-alnum → '-'). Bentrok (LINTAS-TENANT per hub) diatasi dengan sufiks -2, -3, … sampai unik.
     */
    private fun resolveUsername(serverId: UUID, label: String, requested: String?): String {
        val base = requested?.trim()?.takeIf { it.isNotBlank() } ?: defaultUsername(label)
        if (!peerRepository.existsByServerIdAndUsername(serverId, base)) return base
        var suffix = 2
        while (peerRepository.existsByServerIdAndUsername(serverId, "$base-$suffix")) suffix++
        return "$base-$suffix"
    }

    private fun defaultUsername(label: String): String {
        val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.ifBlank { "akun" }
    }

    /** Akun harus ada DAN milik tenant aktif; jika bukan, samarkan sebagai tidak ditemukan. */
    private fun ownedPeer(id: UUID): VpnPeer {
        val peer = peerRepository.findById(id)?.takeIf { it.tenantId == TenantContext.tenantId() }
        return peer ?: throw NotFoundException("Akun VPN $id tidak ditemukan")
    }

    private fun requireServer(id: UUID): VpnServer =
        serverRepository.findById(id) ?: throw NotFoundException("Server VPN $id tidak ditemukan")

    private fun serverFor(id: UUID, cache: MutableMap<UUID, VpnServer>): VpnServer =
        cache.getOrPut(id) { requireServer(id) }

    private fun VpnPeer.toView(server: VpnServer, revealPassword: Boolean = false): VpnAccountView {
        val protocol = server.protocol.name
        // Aturannya milik domain (hub TCP-lah yang bisa di-dial v6), bukan diulang di sini.
        val supportsV6 = server.servesRouterOsV6
        return VpnAccountView(
            id = id,
            label = name,
            serverName = server.name,
            host = server.host,
            port = server.port,
            protocol = protocol,
            cipher = CIPHER,
            securityType = "OpenVPN ($protocol) · $CIPHER",
            username = username,
            overlayIp = overlayIp,
            remotePort = remotePort,
            winboxAddress = remotePort?.let { "${server.host}:$it" },
            forwards = forwards.map {
                VpnPortForwardView(
                    id = it.id,
                    label = it.label,
                    publicPort = it.publicPort,
                    devicePort = it.devicePort,
                    protocol = it.protocol.name,
                    address = "${server.host}:${it.publicPort}",
                )
            },
            routes = routes.map { VpnRoutedSubnetView(id = it.id, label = it.label, cidr = it.cidr) },
            status = status.name,
            online = online,
            lastHandshakeAt = lastHandshakeAt,
            supportsV6 = supportsV6,
            password = if (revealPassword) password else null,
            routerOsCommand = if (revealPassword) renderer.renderRouterOsCommand(server, this) else null,
            routerOsCommandV6 = if (revealPassword && supportsV6) renderer.renderRouterOsCommandV6(server, this) else null,
        )
    }

    private companion object {
        /** Cipher tunnel — harus selaras dengan yang dirender [VpnConfigRenderer]. */
        const val CIPHER = "AES-256-GCM"
        const val DEFAULT_LABEL = "Akun VPN"
    }
}
