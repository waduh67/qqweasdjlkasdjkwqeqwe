package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.vpn.application.port.outbound.VpnPeerRepository
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.application.service.VpnConfigRenderer
import com.duluin.ftth.vpn.application.service.VpnProvisioningReader
import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji pencatatan liveness di [VpnProvisioningReader] dengan fake repository in-memory —
 * tanpa DB. Fokus wiring: peer dikenal → online tersimpan lewat save; peer asing → false, tanpa save.
 */
class VpnProvisioningLivenessTest {

    private val serverId = UuidV7.generate()

    private fun newPeer(username: String): VpnPeer = VpnPeer.create(
        tenantId = UuidV7.generate(),
        serverId = serverId,
        name = "BRAS Jakarta 1",
        username = username,
        overlayIp = "10.8.0.2",
        remotePort = 20000,
        password = "initialpassword123",
        deviceType = null,
        deviceId = null,
    )

    @Test
    fun `recordConnect menandai peer online dan menyimpannya`() {
        val repo = FakePeerRepository().apply { seed(newPeer("bras-jakarta-1")) }
        val reader = VpnProvisioningReader(NoServerRepository, repo, VpnConfigRenderer())

        val updated = reader.recordConnect(serverId, "bras-jakarta-1")

        assertThat(updated).isTrue()
        assertThat(repo.saved.single().online).isTrue()
        assertThat(repo.saved.single().lastHandshakeAt).isNotNull()
    }

    @Test
    fun `recordDisconnect menandai peer offline dan menyimpannya`() {
        val peer = newPeer("bras-jakarta-1").apply { markConnected(java.time.Instant.now()) }
        val repo = FakePeerRepository().apply { seed(peer) }
        val reader = VpnProvisioningReader(NoServerRepository, repo, VpnConfigRenderer())

        val updated = reader.recordDisconnect(serverId, "bras-jakarta-1")

        assertThat(updated).isTrue()
        assertThat(repo.saved.single().online).isFalse()
    }

    @Test
    fun `record untuk peer asing mengembalikan false tanpa menyimpan`() {
        val repo = FakePeerRepository()
        val reader = VpnProvisioningReader(NoServerRepository, repo, VpnConfigRenderer())

        assertThat(reader.recordConnect(serverId, "tak-ada")).isFalse()
        assertThat(reader.recordDisconnect(serverId, "tak-ada")).isFalse()
        assertThat(repo.saved).isEmpty()
    }

    /** Fake peer repo: hanya seed + findByServerIdAndUsername + save yang terpakai jalur liveness. */
    private class FakePeerRepository : VpnPeerRepository {
        private val store = mutableMapOf<Pair<UUID, String>, VpnPeer>()
        val saved = mutableListOf<VpnPeer>()

        fun seed(peer: VpnPeer) {
            store[peer.serverId to peer.username] = peer
        }

        override fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeer? =
            store[serverId to username]

        override fun save(peer: VpnPeer): VpnPeer {
            saved += peer
            store[peer.serverId to peer.username] = peer
            return peer
        }

        override fun findById(id: UUID): VpnPeer? = throw NotImplementedError()
        override fun findByTenant(tenantId: UUID): List<VpnPeer> = throw NotImplementedError()
        override fun findByServerId(serverId: UUID): List<VpnPeer> = throw NotImplementedError()
        override fun usedOverlayIps(serverId: UUID): Set<String> = throw NotImplementedError()
        override fun usedRemotePorts(serverId: UUID): Set<Int> = throw NotImplementedError()
        override fun routedCidrsByServerIdExcluding(serverId: UUID, peerId: UUID): List<String> =
            throw NotImplementedError()
        override fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean = throw NotImplementedError()
        override fun countByServerId(serverId: UUID): Long = throw NotImplementedError()
        override fun deleteById(id: UUID) = throw NotImplementedError()
    }

    /** Server repo tak terpakai jalur liveness — semua operasi dianggap kesalahan bila terpanggil. */
    private object NoServerRepository : VpnServerRepository {
        override fun save(server: VpnServer): VpnServer = throw NotImplementedError()
        override fun findById(id: UUID): VpnServer? = throw NotImplementedError()
        override fun findAll(): List<VpnServer> = throw NotImplementedError()
        override fun findAssignable(): List<VpnServer> = throw NotImplementedError()
        override fun delete(id: UUID) = throw NotImplementedError()
    }
}
