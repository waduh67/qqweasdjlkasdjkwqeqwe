package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.vpn.application.port.inbound.CreateVpnPeerCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnPeerUseCase
import com.duluin.ftth.vpn.application.port.inbound.VpnPeerView
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class VpnPeerService(
    private val serverRepository: VpnServerRepository,
    private val peerRepository: VpnPeerRepository,
    private val renderer: VpnConfigRenderer,
    private val passwordGenerator: PasswordGenerator,
    private val auditor: AuditRecorder,
) : ManageVpnPeerUseCase {

    @Transactional(readOnly = true)
    override fun listByServer(serverId: UUID): List<VpnPeerView> =
        peerRepository.findByServerId(serverId).map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): VpnPeerView = requirePeer(id).toView()

    override fun create(serverId: UUID, command: CreateVpnPeerCommand): VpnPeerView {
        val server = requireServer(serverId)
        val overlayIp = TunnelSubnet.parse(server.tunnelCidr).allocate(peerRepository.usedOverlayIps(serverId))
        val username = resolveUsername(serverId, command.name, command.username)
        val peer = VpnPeer.create(
            tenantId = server.tenantId,
            serverId = serverId,
            name = command.name,
            username = username,
            overlayIp = overlayIp,
            password = passwordGenerator.generate(),
            deviceType = command.deviceType,
            deviceId = command.deviceId,
        )
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.peer.created", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "overlayIp" to saved.overlayIp, "serverId" to serverId),
        )
        return saved.toView()
    }

    override fun enable(id: UUID): VpnPeerView {
        val peer = requirePeer(id)
        peer.enable()
        val saved = peerRepository.save(peer)
        auditor.record("vpn.peer.enabled", "VpnPeer", saved.id, saved.tenantId, mapOf("username" to saved.username))
        return saved.toView()
    }

    override fun disable(id: UUID): VpnPeerView {
        val peer = requirePeer(id)
        peer.disable()
        val saved = peerRepository.save(peer)
        auditor.record("vpn.peer.disabled", "VpnPeer", saved.id, saved.tenantId, mapOf("username" to saved.username))
        return saved.toView()
    }

    override fun rotatePassword(id: UUID): VpnPeerView {
        val peer = requirePeer(id)
        peer.rotatePassword(passwordGenerator.generate())
        val saved = peerRepository.save(peer)
        auditor.record(
            "vpn.peer.password-rotated", "VpnPeer", saved.id, saved.tenantId,
            mapOf("username" to saved.username),
        )
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val peer = requirePeer(id)
        peerRepository.deleteById(id)
        auditor.record("vpn.peer.deleted", "VpnPeer", id, peer.tenantId, mapOf("username" to peer.username))
    }

    @Transactional(readOnly = true)
    override fun renderOvpn(id: UUID): String {
        val peer = requirePeer(id)
        return renderer.renderOvpn(requireServer(peer.serverId), peer)
    }

    @Transactional(readOnly = true)
    override fun renderRouterOs(id: UUID): String {
        val peer = requirePeer(id)
        return renderer.renderRouterOs(requireServer(peer.serverId), peer)
    }

    /**
     * Username eksplisit dipakai apa adanya; bila kosong, diturunkan dari nama (huruf-kecil,
     * non-alnum → '-'). Bentrok diatasi dengan menambah sufiks -2, -3, … sampai unik.
     */
    private fun resolveUsername(serverId: UUID, name: String, requested: String?): String {
        val base = requested?.trim()?.takeIf { it.isNotBlank() } ?: defaultUsername(name)
        if (!peerRepository.existsByServerIdAndUsername(serverId, base)) return base
        var suffix = 2
        while (peerRepository.existsByServerIdAndUsername(serverId, "$base-$suffix")) suffix++
        return "$base-$suffix"
    }

    private fun defaultUsername(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.ifBlank { "peer" }
    }

    private fun requirePeer(id: UUID): VpnPeer =
        peerRepository.findById(id) ?: throw NotFoundException("Peer VPN $id tidak ditemukan")

    private fun requireServer(id: UUID): VpnServer =
        serverRepository.findById(id) ?: throw NotFoundException("Server VPN $id tidak ditemukan")

    private fun VpnPeer.toView() = VpnPeerView(
        id = id,
        serverId = serverId,
        name = name,
        username = username,
        overlayIp = overlayIp,
        status = status.name,
        deviceType = deviceType,
        deviceId = deviceId,
        lastHandshakeAt = lastHandshakeAt,
    )
}
