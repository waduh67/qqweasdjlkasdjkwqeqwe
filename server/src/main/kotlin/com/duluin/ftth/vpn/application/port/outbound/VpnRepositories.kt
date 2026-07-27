package com.duluin.ftth.vpn.application.port.outbound

import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnServer
import java.util.UUID

/**
 * Port persistence module vpn. Kedua tabel tenant-aware (@TenantId + RLS), jadi semua
 * pencarian ter-scope tenant aktif secara otomatis — tak ada parameter tenantId.
 */
interface VpnServerRepository {

    fun save(server: VpnServer): VpnServer

    fun findById(id: UUID): VpnServer?

    /** Semua hub tenant aktif, terurut nama. */
    fun findAll(): List<VpnServer>

    fun delete(id: UUID)
}

interface VpnPeerRepository {

    fun save(peer: VpnPeer): VpnPeer

    fun findById(id: UUID): VpnPeer?

    /** Peer sebuah hub, terurut IP overlay. */
    fun findByServerId(serverId: UUID): List<VpnPeer>

    /** IP overlay yang sudah terpakai pada sebuah hub — dasar alokasi berikutnya. */
    fun usedOverlayIps(serverId: UUID): Set<String>

    fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean

    fun countByServerId(serverId: UUID): Long

    fun deleteById(id: UUID)
}
