package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.vpn.application.port.inbound.CreateVpnServerCommand
import com.duluin.ftth.vpn.application.port.inbound.ManageVpnServerUseCase
import com.duluin.ftth.vpn.application.port.inbound.ServerConfigView
import com.duluin.ftth.vpn.application.port.inbound.UpdateVpnServerCommand
import com.duluin.ftth.vpn.application.port.inbound.VpnServerView
import com.duluin.ftth.vpn.application.port.outbound.ServerPkiIssuer
import com.duluin.ftth.vpn.application.port.outbound.VpnNodeTokenRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.config.VpnProperties
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnNodeToken
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class VpnServerService(
    private val serverRepository: VpnServerRepository,
    private val peerRepository: VpnPeerRepository,
    private val nodeTokenRepository: VpnNodeTokenRepository,
    private val renderer: VpnConfigRenderer,
    private val properties: VpnProperties,
    private val pkiIssuer: ServerPkiIssuer,
    private val auditor: AuditRecorder,
) : ManageVpnServerUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<VpnServerView> = serverRepository.findAll().map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): VpnServerView = require(id).toView()

    override fun create(command: CreateVpnServerCommand): VpnServerView {
        val server = VpnServer.create(
            tenantId = TenantContext.tenantId(),
            name = command.name,
            host = command.host,
            port = command.port ?: properties.defaultPort,
            protocol = command.protocol ?: VpnProtocol.valueOf(properties.defaultProtocol),
            tunnelCidr = command.tunnelCidr ?: properties.defaultTunnelCidr,
        )
        // Aplikasi menjadi CA-nya sendiri: terbitkan CA + sertifikat server saat hub dibuat,
        // sehingga operator tak perlu easy-rsa manual.
        val pki = pkiIssuer.issueForServer(server.name)
        server.attachPki(pki.caCertPem, pki.caKeyPem, pki.serverCertPem, pki.serverKeyPem)
        val saved = serverRepository.save(server)
        // Token node menyertai hub sejak lahir: dari sinilah perintah pasang satu-baris berasal.
        val rawToken = issueNodeToken(saved.id, saved.tenantId)
        auditor.record(
            "vpn.server.created", "VpnServer", saved.id, saved.tenantId,
            mapOf("name" to saved.name, "pkiReady" to saved.pkiReady),
        )
        return saved.toView(rawNodeToken = rawToken)
    }

    override fun regenerateNodeToken(id: UUID): VpnServerView {
        val server = require(id)
        val rawToken = issueNodeToken(server.id, server.tenantId)
        auditor.record("vpn.server.token-regenerated", "VpnServer", server.id, server.tenantId, emptyMap())
        return server.toView(rawNodeToken = rawToken)
    }

    /** Satu token aktif per hub: buang yang lama lalu terbitkan baru, kembalikan yang mentah (sekali tampil). */
    private fun issueNodeToken(serverId: UUID, tenantId: UUID): String {
        nodeTokenRepository.deleteByServerId(serverId)
        val (token, raw) = VpnNodeToken.issue(serverId, tenantId)
        nodeTokenRepository.save(token)
        return raw
    }

    override fun update(id: UUID, command: UpdateVpnServerCommand): VpnServerView {
        val server = require(id)
        server.rename(command.name)
        server.updateEndpoint(command.host, command.port, command.protocol)
        val saved = serverRepository.save(server)
        auditor.record("vpn.server.updated", "VpnServer", saved.id, saved.tenantId, mapOf("name" to saved.name))
        return saved.toView()
    }

    override fun setCredentials(id: UUID, caCertPem: String?, tlsAuthKey: String?): VpnServerView {
        val server = require(id)
        server.setCredentials(caCertPem, tlsAuthKey)
        val saved = serverRepository.save(server)
        auditor.record(
            "vpn.server.credentials-set", "VpnServer", saved.id, saved.tenantId,
            mapOf("hasCaCert" to (saved.caCertPem != null), "hasTlsAuth" to (saved.tlsAuthKey != null)),
        )
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val server = require(id)
        val peers = peerRepository.countByServerId(id)
        if (peers > 0) {
            throw ConflictException("Server VPN '${server.name}' masih punya $peers peer, hapus dulu")
        }
        serverRepository.delete(id)
        auditor.record("vpn.server.deleted", "VpnServer", id, server.tenantId, mapOf("name" to server.name))
    }

    @Transactional(readOnly = true)
    override fun renderServerConfig(id: UUID): ServerConfigView {
        val server = require(id)
        return renderer.renderServerConfig(server, peerRepository.findByServerId(id))
    }

    private fun require(id: UUID): VpnServer =
        serverRepository.findById(id) ?: throw NotFoundException("Server VPN $id tidak ditemukan")

    private fun VpnServer.toView(rawNodeToken: String? = null) = VpnServerView(
        id = id,
        name = name,
        host = host,
        port = port,
        protocol = protocol.name,
        tunnelCidr = tunnelCidr,
        serverAddress = TunnelSubnet.parse(tunnelCidr).serverAddress(),
        status = status.name,
        hasCaCert = caCertPem != null,
        hasTlsAuth = tlsAuthKey != null,
        pkiReady = pkiReady,
        peerCount = peerRepository.countByServerId(id),
        nodeToken = rawNodeToken,
        installCommand = rawNodeToken?.let(::buildInstallCommand),
    )

    /** Perintah pasang satu-baris; pakai base-URL publik bila diset, jika kosong beri placeholder jelas. */
    private fun buildInstallCommand(rawToken: String): String {
        val base = properties.publicBaseUrl.trimEnd('/').ifBlank { "<URL-APLIKASI-ANDA>" }
        return "curl -fsSL \"$base/api/vpn/provision/install.sh?token=$rawToken\" | sudo bash"
    }
}
