package com.duluin.ftth.vpn.adapter.outbound.persistence

import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.vpn.application.port.outbound.VpnNodeRef
import com.duluin.ftth.vpn.application.port.outbound.VpnNodeTokenRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.domain.model.VpnNodeToken
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnPeerRoute
import com.duluin.ftth.vpn.domain.model.VpnPortForward
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
 *
 * Penerusan port dan blok di belakang perangkat ikut disimpan di sini karena keduanya bagian
 * dari agregat peer (tabel anak `vpn_port_forward` & `vpn_peer_route`, disinkronkan per-id:
 * yang hilang dihapus, yang ada disegarkan).
 */
@Component
class VpnPeerPersistenceAdapter(
    private val jpa: VpnPeerJpaRepository,
    private val forwardJpa: VpnPortForwardJpaRepository,
    private val routeJpa: VpnPeerRouteJpaRepository,
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
            online = peer.online
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
            online = peer.online,
            password = encryptedPassword ?: error("Password peer VPN wajib diisi"),
        )
        val saved = jpa.save(entity)
        return saved.toDomain(syncForwards(peer), syncRoutes(peer))
    }

    override fun findById(id: UUID): VpnPeer? =
        jpa.findById(id).orElse(null)?.let {
            it.toDomain(
                forwardJpa.findByPeerIdOrderByPublicPortAsc(it.id),
                routeJpa.findByPeerIdOrderByCidrAsc(it.id),
            )
        }

    override fun findByTenant(tenantId: UUID): List<VpnPeer> =
        jpa.findByTenantIdOrderByNameAsc(tenantId).toDomainAll()

    override fun findByServerId(serverId: UUID): List<VpnPeer> =
        jpa.findByServerIdOrderByOverlayIpAsc(serverId).toDomainAll()

    override fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeer? =
        jpa.findByServerIdAndUsername(serverId, username)?.let {
            it.toDomain(
                forwardJpa.findByPeerIdOrderByPublicPortAsc(it.id),
                routeJpa.findByPeerIdOrderByCidrAsc(it.id),
            )
        }

    override fun usedOverlayIps(serverId: UUID): Set<String> =
        jpa.findByServerIdOrderByOverlayIpAsc(serverId).mapTo(HashSet()) { it.overlayIp }

    override fun usedRemotePorts(serverId: UUID): Set<Int> =
        forwardJpa.findPublicPortsByServerId(serverId).toHashSet()

    override fun routedCidrsByServerIdExcluding(serverId: UUID, peerId: UUID): List<String> =
        routeJpa.findByServerIdOrderByCidrAsc(serverId).filter { it.peerId != peerId }.map { it.cidr }

    override fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean =
        jpa.existsByServerIdAndUsername(serverId, username)

    override fun countByServerId(serverId: UUID): Long = jpa.countByServerId(serverId)

    override fun deleteById(id: UUID) {
        // Anak lebih dulu, seketika: FK di DB memang ON DELETE CASCADE, tapi menghapusnya
        // eksplisit membuat urutannya tak bergantung pada penjadwalan flush Hibernate.
        forwardJpa.deleteByPeerId(id)
        routeJpa.deleteByPeerId(id)
        jpa.deleteById(id)
    }

    /**
     * Selaraskan tabel anak dengan isi agregat: baris yang tak lagi ada di domain dihapus lebih
     * dulu (di-flush, supaya port publiknya bebas sebelum INSERT apa pun menyentuh UNIQUE
     * (server_id, public_port)), baru sisanya di-upsert.
     */
    private fun syncForwards(peer: VpnPeer): List<VpnPortForwardJpaEntity> {
        val existing = forwardJpa.findByPeerIdOrderByPublicPortAsc(peer.id).associateBy { it.id }
        val wanted = peer.forwards.associateBy { it.id }
        val stale = existing.values.filter { it.id !in wanted.keys }
        if (stale.isNotEmpty()) {
            forwardJpa.deleteAll(stale)
            forwardJpa.flush()
        }
        return peer.forwards.map { forward ->
            val entity = existing[forward.id]?.apply {
                // publicPort identitas, tak disentuh; yang berubah hanya sasaran & namanya.
                label = forward.label
                devicePort = forward.devicePort
                protocol = forward.protocol
            } ?: VpnPortForwardJpaEntity(
                id = forward.id,
                peerId = peer.id,
                serverId = peer.serverId,
                label = forward.label,
                publicPort = forward.publicPort,
                devicePort = forward.devicePort,
                protocol = forward.protocol,
            )
            forwardJpa.save(entity)
        }
    }

    /**
     * Selaraskan blok di belakang perangkat, pola sama dengan [syncForwards] dan dengan alasan
     * yang sama: yang dicabut harus benar-benar hilang dari DB sebelum INSERT apa pun menyentuh
     * UNIQUE (server_id, cidr) — kalau tidak, memindahkan satu blok dari akun A ke akun B dalam
     * satu transaksi akan ditolak oleh constraint padahal maksudnya sah.
     */
    private fun syncRoutes(peer: VpnPeer): List<VpnPeerRouteJpaEntity> {
        val existing = routeJpa.findByPeerIdOrderByCidrAsc(peer.id).associateBy { it.id }
        val wanted = peer.routes.associateBy { it.id }
        val stale = existing.values.filter { it.id !in wanted.keys }
        if (stale.isNotEmpty()) {
            routeJpa.deleteAll(stale)
            routeJpa.flush()
        }
        return peer.routes.map { route ->
            // cidr identitas, tak disentuh; yang bisa berubah cuma namanya.
            val entity = existing[route.id]?.apply { label = route.label }
                ?: VpnPeerRouteJpaEntity(
                    id = route.id,
                    peerId = peer.id,
                    serverId = peer.serverId,
                    label = route.label,
                    cidr = route.cidr,
                )
            routeJpa.save(entity)
        }
    }

    /** Muat anak sekali untuk seluruh daftar (hindari N+1 saat dashboard menampilkan akun). */
    private fun List<VpnPeerJpaEntity>.toDomainAll(): List<VpnPeer> {
        if (isEmpty()) return emptyList()
        val ids = map { it.id }
        val forwardsByPeer = forwardJpa.findByPeerIdInOrderByPublicPortAsc(ids).groupBy { it.peerId }
        val routesByPeer = routeJpa.findByPeerIdInOrderByCidrAsc(ids).groupBy { it.peerId }
        return map { it.toDomain(forwardsByPeer[it.id].orEmpty(), routesByPeer[it.id].orEmpty()) }
    }

    private fun VpnPeerJpaEntity.toDomain(
        forwards: List<VpnPortForwardJpaEntity>,
        routes: List<VpnPeerRouteJpaEntity>,
    ): VpnPeer = VpnPeer.rehydrate(
        id = id,
        tenantId = tenantId,
        serverId = serverId,
        name = name,
        username = username,
        overlayIp = overlayIp,
        forwards = forwards.map {
            VpnPortForward.rehydrate(
                id = it.id,
                publicPort = it.publicPort,
                label = it.label,
                devicePort = it.devicePort,
                protocol = it.protocol,
            )
        },
        routes = routes.map { VpnPeerRoute.rehydrate(id = it.id, cidr = it.cidr, label = it.label) },
        status = status,
        deviceType = deviceType,
        deviceId = deviceId,
        lastHandshakeAt = lastHandshakeAt,
        online = online,
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
