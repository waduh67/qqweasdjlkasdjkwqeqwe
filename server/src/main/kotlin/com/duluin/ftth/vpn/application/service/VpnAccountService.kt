package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.vpn.application.port.inbound.GenerateVpnAccountCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnAccountUseCase
import com.duluin.ftth.vpn.application.port.inbound.VpnAccountView
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.config.VpnProperties
import com.duluin.ftth.vpn.domain.model.RemotePortRange
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnClientVariant
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnProtocol
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
        // Hub TCP menyajikan GCM+CBC (NCP) → juga melayani RouterOS v6.
        val supportsV6 = server.protocol == VpnProtocol.TCP
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
            winboxAddress = "${server.host}:$remotePort",
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
