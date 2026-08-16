package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.vpn.application.port.inbound.VpnRouteCommand
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.application.service.PasswordGenerator
import com.duluin.ftth.vpn.application.service.VpnAccountService
import com.duluin.ftth.vpn.application.service.VpnConfigRenderer
import com.duluin.ftth.vpn.config.VpnProperties
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji penjaga blok LINTAS-AKUN di [VpnAccountService] dengan fake repository — tanpa DB.
 *
 * Dipisah dari uji end-to-end karena hub dipilih otomatis (yang terlengang), sehingga dua akun
 * tak bisa dipaksa mendarat di hub yang sama lewat HTTP. Padahal justru di situlah bahayanya:
 * satu hub punya SATU tabel rute, jadi dua akun (bahkan milik tenant berbeda) yang mengklaim blok
 * beririsan membuat OpenVPN diam-diam memilih salah satu pemiliknya — tanpa galat, tanpa log.
 */
class VpnAccountRouteTest {

    private val tenantId = UuidV7.generate()
    private val server = VpnServer.create(
        name = "Hub Uji",
        host = "vpn.example.com",
        port = 1194,
        protocol = VpnProtocol.UDP,
        tunnelCidr = "10.8.0.0/24",
    )

    private fun newService(repo: FakePeerRepository) = VpnAccountService(
        serverRepository = FixedServerRepository(server),
        peerRepository = repo,
        renderer = VpnConfigRenderer(),
        passwordGenerator = PasswordGenerator(),
        properties = VpnProperties(),
        auditor = AuditRecorder({ }, NoCurrentUser),
    )

    private fun newPeer(username: String): VpnPeer = VpnPeer.create(
        tenantId = tenantId,
        serverId = server.id,
        name = "BRAS $username",
        username = username,
        overlayIp = "10.8.0.2",
        remotePort = 20000,
        password = "initialpassword123",
        deviceType = null,
        deviceId = null,
    )

    private fun <T> asTenant(block: () -> T): T = TenantContext.runAs(tenantId, block)

    @Test
    fun `blok yang beririsan dengan milik akun lain di hub yang sama ditolak`() {
        val peer = newPeer("bras-satu")
        val repo = FakePeerRepository(peer, claimedByOthers = listOf("10.20.0.0/16"))
        val service = newService(repo)

        assertThatThrownBy {
            asTenant { service.addRoute(peer.id, VpnRouteCommand(label = null, cidr = "10.20.5.0/24")) }
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `blok yang tak beririsan dengan tetangga tetap boleh`() {
        val peer = newPeer("bras-dua")
        val repo = FakePeerRepository(peer, claimedByOthers = listOf("10.20.0.0/16"))
        val service = newService(repo)

        val view = asTenant { service.addRoute(peer.id, VpnRouteCommand(label = null, cidr = "10.21.0.0/16")) }

        assertThat(view.routes.map { it.cidr }).containsExactly("10.21.0.0/16")
    }

    @Test
    fun `blok yang menelan subnet tunnel hub ditolak`() {
        val peer = newPeer("bras-tiga")
        val service = newService(FakePeerRepository(peer))

        // 10.8.0.0/16 memuat tunnel 10.8.0.0/24: rutenya akan menimpa jalan pulang tunnel itu
        // sendiri, dan yang putus bukan cuma akun ini melainkan SEMUA peer di hub.
        assertThatThrownBy {
            asTenant { service.addRoute(peer.id, VpnRouteCommand(label = null, cidr = "10.8.0.0/16")) }
        }.isInstanceOf(ConflictException::class.java)
    }

    /** Fake peer repo: hanya jalur addRoute yang terpakai — findById, save, dan klaim tetangga. */
    private class FakePeerRepository(
        private val peer: VpnPeer,
        private val claimedByOthers: List<String> = emptyList(),
    ) : VpnPeerRepository {
        override fun findById(id: UUID): VpnPeer? = peer.takeIf { it.id == id }
        override fun save(peer: VpnPeer): VpnPeer = peer
        override fun routedCidrsByServerIdExcluding(serverId: UUID, peerId: UUID): List<String> = claimedByOthers

        override fun findByTenant(tenantId: UUID): List<VpnPeer> = throw NotImplementedError()
        override fun findByServerId(serverId: UUID): List<VpnPeer> = throw NotImplementedError()
        override fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeer? = throw NotImplementedError()
        override fun usedOverlayIps(serverId: UUID): Set<String> = throw NotImplementedError()
        override fun usedRemotePorts(serverId: UUID): Set<Int> = throw NotImplementedError()
        override fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean = throw NotImplementedError()
        override fun countByServerId(serverId: UUID): Long = throw NotImplementedError()
        override fun deleteById(id: UUID) = throw NotImplementedError()
    }

    /** Satu-satunya hub yang ada; auto-assign tak dipakai di jalur yang diuji. */
    private class FixedServerRepository(private val server: VpnServer) : VpnServerRepository {
        override fun findById(id: UUID): VpnServer? = server.takeIf { it.id == id }
        override fun findAll(): List<VpnServer> = listOf(server)
        override fun findAssignable(): List<VpnServer> = listOf(server)
        override fun save(server: VpnServer): VpnServer = throw NotImplementedError()
        override fun delete(id: UUID) = throw NotImplementedError()
    }

    /** Jejak audit tetap tercatat; aktornya kosong karena tak ada request yang membungkus. */
    private object NoCurrentUser : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser? = null
    }
}
