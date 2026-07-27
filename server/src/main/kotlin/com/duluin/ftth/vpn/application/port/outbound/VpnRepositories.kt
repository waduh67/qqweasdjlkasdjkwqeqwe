package com.duluin.ftth.vpn.application.port.outbound

import com.duluin.ftth.vpn.domain.model.VpnPeer
import com.duluin.ftth.vpn.domain.model.VpnServer
import java.util.UUID

/**
 * Port persistence hub VPN. Hub adalah infrastruktur PLATFORM (tanpa tenant/RLS): pencarian
 * lintas seluruh hub, dikelola hanya oleh admin platform.
 */
interface VpnServerRepository {

    fun save(server: VpnServer): VpnServer

    fun findById(id: UUID): VpnServer?

    /** Semua hub platform, terurut nama. */
    fun findAll(): List<VpnServer>

    /**
     * Hub yang siap menerima akun baru (ACTIVE + PKI lengkap), terurut agar auto-assign
     * deterministik. Pemanggil memilih yang paling lengang (peer paling sedikit).
     */
    fun findAssignable(): List<VpnServer>

    fun delete(id: UUID)
}

/**
 * Port persistence akun VPN (peer). Tabelnya TANPA RLS (cermin collector): sebagian query
 * di-scope tenant di aplikasi (daftar/kelola milik tenant), sebagian lintas-tenant per hub
 * (alokasi IP, keunikan username, resolusi callback) — sebab satu hub dibagi banyak tenant.
 */
interface VpnPeerRepository {

    fun save(peer: VpnPeer): VpnPeer

    fun findById(id: UUID): VpnPeer?

    /** Akun milik satu tenant, terurut nama — daftar untuk dashboard tenant. */
    fun findByTenant(tenantId: UUID): List<VpnPeer>

    /** Peer sebuah hub (lintas-tenant), terurut IP overlay — dipakai render config server. */
    fun findByServerId(serverId: UUID): List<VpnPeer>

    /** Satu peer via (hub, username) LINTAS-TENANT — dipakai callback provisioning. */
    fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeer?

    /** IP overlay terpakai pada sebuah hub (lintas-tenant) — dasar alokasi berikutnya. */
    fun usedOverlayIps(serverId: UUID): Set<String>

    /** Port remote (publik TCP) terpakai pada sebuah hub (lintas-tenant) — dasar alokasi berikutnya. */
    fun usedRemotePorts(serverId: UUID): Set<Int>

    /** Keunikan username per hub, LINTAS-TENANT. */
    fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean

    /** Jumlah peer sebuah hub (lintas-tenant) — kapasitas untuk auto-assign & tampilan. */
    fun countByServerId(serverId: UUID): Long

    fun deleteById(id: UUID)
}
