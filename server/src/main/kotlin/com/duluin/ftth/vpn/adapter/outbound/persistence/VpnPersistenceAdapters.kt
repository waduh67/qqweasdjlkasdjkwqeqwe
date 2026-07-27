package com.duluin.ftth.vpn.adapter.outbound.persistence

import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.vpn.application.port.outbound.VpnNodeRef
import com.duluin.ftth.vpn.application.port.outbound.VpnNodeTokenRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.domain.model.VpnNodeToken
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnServer
import com.duluin.ftth.vpn.domain.model.VpnServerStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Adapter hub VPN sekaligus batas enkripsi: domain memegang CA/tls-auth apa adanya,
 * database hanya pernah melihat ciphertext (sama seperti secret CoA/SNMP di module lain).
 */
@Component
class VpnServerPersistenceAdapter(
    private val jpa: VpnServerJpaRepository,
    private val cipher: SecretCipher,
) : VpnServerRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(server: VpnServer): VpnServer {
        val encryptedCa = server.caCertPem?.let(cipher::encrypt)
        val encryptedTlsAuth = server.tlsAuthKey?.let(cipher::encrypt)
        // Kunci privat CA & server juga rahasia → terenkripsi; sertifikat server publik.
        val encryptedCaKey = server.caKeyPem?.let(cipher::encrypt)
        val encryptedServerKey = server.serverKeyPem?.let(cipher::encrypt)
        val entity = jpa.findById(server.id).orElse(null)?.apply {
            name = server.name
            host = server.host
            port = server.port
            protocol = server.protocol
            tunnelCidr = server.tunnelCidr
            status = server.status
            caCert = encryptedCa
            tlsAuthKey = encryptedTlsAuth
            caKey = encryptedCaKey
            serverCert = server.serverCertPem
            serverKey = encryptedServerKey
        } ?: VpnServerJpaEntity(
            id = server.id,
            name = server.name,
            host = server.host,
            port = server.port,
            protocol = server.protocol,
            tunnelCidr = server.tunnelCidr,
            status = server.status,
            caCert = encryptedCa,
            tlsAuthKey = encryptedTlsAuth,
            caKey = encryptedCaKey,
            serverCert = server.serverCertPem,
            serverKey = encryptedServerKey,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): VpnServer? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<VpnServer> = jpa.findAllByOrderByNameAsc().map { it.toDomain() }

    /** Hub siap-pakai untuk auto-assign: ACTIVE + PKI lengkap, terurut nama (deterministik). */
    override fun findAssignable(): List<VpnServer> =
        jpa.findByStatusOrderByNameAsc(VpnServerStatus.ACTIVE).map { it.toDomain() }.filter { it.pkiReady }

    override fun delete(id: UUID) = jpa.deleteById(id)

    private fun VpnServerJpaEntity.toDomain(): VpnServer = VpnServer.rehydrate(
        id = id,
        name = name,
        host = host,
        port = port,
        protocol = protocol,
        tunnelCidr = tunnelCidr,
        status = status,
        caCertPem = cipher.decryptQuietly(caCert, name, log),
        tlsAuthKey = cipher.decryptQuietly(tlsAuthKey, name, log),
        caKeyPem = cipher.decryptQuietly(caKey, name, log),
        serverCertPem = serverCert,
        serverKeyPem = cipher.decryptQuietly(serverKey, name, log),
    )
}

/**
 * Adapter peer sekaligus batas enkripsi password. Saat memperbarui, password hanya
 * ditulis ulang bila domain memegang nilai asli — sentinel kosong dari kegagalan dekripsi
 * tidak menimpa ciphertext yang ada, agar penyuntingan field lain tak merusak password.
 */
@Component
class VpnPeerPersistenceAdapter(
    private val jpa: VpnPeerJpaRepository,
    private val cipher: SecretCipher,
) : VpnPeerRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(peer: VpnPeer): VpnPeer {
        val encryptedPassword = peer.password.takeIf { it.isNotBlank() }?.let(cipher::encrypt)
        val entity = jpa.findById(peer.id).orElse(null)?.apply {
            // Identitas (serverId, username, overlayIp) tak disentuh.
            name = peer.name
            status = peer.status
            deviceType = peer.deviceType
            deviceId = peer.deviceId
            lastHandshakeAt = peer.lastHandshakeAt
            if (encryptedPassword != null) password = encryptedPassword
        } ?: VpnPeerJpaEntity(
            id = peer.id,
            tenantId = peer.tenantId,
            serverId = peer.serverId,
            name = peer.name,
            username = peer.username,
            overlayIp = peer.overlayIp,
            status = peer.status,
            deviceType = peer.deviceType,
            deviceId = peer.deviceId,
            lastHandshakeAt = peer.lastHandshakeAt,
            password = encryptedPassword ?: error("Password peer VPN wajib diisi"),
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): VpnPeer? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByTenant(tenantId: UUID): List<VpnPeer> =
        jpa.findByTenantIdOrderByNameAsc(tenantId).map { it.toDomain() }

    override fun findByServerId(serverId: UUID): List<VpnPeer> =
        jpa.findByServerIdOrderByOverlayIpAsc(serverId).map { it.toDomain() }

    override fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeer? =
        jpa.findByServerIdAndUsername(serverId, username)?.toDomain()

    override fun usedOverlayIps(serverId: UUID): Set<String> =
        jpa.findByServerIdOrderByOverlayIpAsc(serverId).mapTo(HashSet()) { it.overlayIp }

    override fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean =
        jpa.existsByServerIdAndUsername(serverId, username)

    override fun countByServerId(serverId: UUID): Long = jpa.countByServerId(serverId)

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    private fun VpnPeerJpaEntity.toDomain(): VpnPeer = VpnPeer.rehydrate(
        id = id,
        tenantId = tenantId,
        serverId = serverId,
        name = name,
        username = username,
        overlayIp = overlayIp,
        status = status,
        deviceType = deviceType,
        deviceId = deviceId,
        lastHandshakeAt = lastHandshakeAt,
        // Password tak pernah dibaca balik lewat pandangan biasa; sentinel kosong bila tak
        // terdekripsi tidak masalah untuk baca, dan save menjaganya agar tak menimpa.
        password = cipher.decryptQuietly(password, username, log) ?: "",
    )
}

/**
 * Adapter token node. Tabelnya tanpa RLS/@TenantId (lihat [VpnNodeTokenJpaEntity]), jadi
 * [findRefByTokenHash] mengembalikan hub lintas-tenant — dipakai saat autentikasi node
 * sebelum [TenantContext] dipasang. Hanya hash yang disimpan; tak ada batas enkripsi di sini.
 */
@Component
class VpnNodeTokenPersistenceAdapter(
    private val jpa: VpnNodeTokenJpaRepository,
) : VpnNodeTokenRepository {

    override fun save(token: VpnNodeToken): VpnNodeToken = jpa.save(
        VpnNodeTokenJpaEntity(
            id = token.id,
            serverId = token.serverId,
            tokenHash = token.tokenHash,
            tokenHint = token.tokenHint,
        ),
    ).let { token }

    override fun findRefByTokenHash(tokenHash: String): VpnNodeRef? =
        jpa.findByTokenHash(tokenHash)?.let { VpnNodeRef(serverId = it.serverId) }

    @Transactional
    override fun deleteByServerId(serverId: UUID) = jpa.deleteByServerId(serverId)
}

/**
 * Rahasia yang tidak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tidak boleh
 * menggagalkan pemuatan seluruh daftar; barisnya tetap tampil, hanya kehilangan
 * rahasianya dan bisa diisi ulang operator.
 */
private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Rahasia vpn untuk '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
