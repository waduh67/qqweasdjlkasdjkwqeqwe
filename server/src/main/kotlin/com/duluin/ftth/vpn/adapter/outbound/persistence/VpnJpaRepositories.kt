package com.duluin.ftth.vpn.adapter.outbound.persistence

import com.duluin.ftth.vpn.domain.model.VpnServerStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface VpnServerJpaRepository : JpaRepository<VpnServerJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<VpnServerJpaEntity>
    fun findByStatusOrderByNameAsc(status: VpnServerStatus): List<VpnServerJpaEntity>
}

interface VpnPeerJpaRepository : JpaRepository<VpnPeerJpaEntity, UUID> {
    fun findByTenantIdOrderByNameAsc(tenantId: UUID): List<VpnPeerJpaEntity>
    fun findByServerIdOrderByOverlayIpAsc(serverId: UUID): List<VpnPeerJpaEntity>
    fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeerJpaEntity?
    fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean
    fun countByServerId(serverId: UUID): Long
}

interface VpnPortForwardJpaRepository : JpaRepository<VpnPortForwardJpaEntity, UUID> {
    fun findByPeerIdOrderByPublicPortAsc(peerId: UUID): List<VpnPortForwardJpaEntity>

    /** Muat sekaligus untuk sekumpulan akun — menahan N+1 saat menampilkan daftar akun. */
    fun findByPeerIdInOrderByPublicPortAsc(peerIds: Collection<UUID>): List<VpnPortForwardJpaEntity>

    fun findByServerIdOrderByPublicPortAsc(serverId: UUID): List<VpnPortForwardJpaEntity>

    /** Port publik terpakai pada sebuah hub (lintas-tenant) — dasar alokasi berikutnya. */
    @Query("select f.publicPort from VpnPortForwardJpaEntity f where f.serverId = :serverId")
    fun findPublicPortsByServerId(@Param("serverId") serverId: UUID): List<Int>

    /**
     * Bulk delete (bukan derived load-then-remove): dijalankan SEKETIKA ke DB, jadi baris anak
     * sudah hilang sebelum induknya dihapus — Hibernate tak menjamin urutan itu sendiri.
     */
    @Modifying
    @Query("delete from VpnPortForwardJpaEntity f where f.peerId = :peerId")
    fun deleteByPeerId(@Param("peerId") peerId: UUID)
}

interface VpnNodeTokenJpaRepository : JpaRepository<VpnNodeTokenJpaEntity, UUID> {
    fun findByTokenHash(tokenHash: String): VpnNodeTokenJpaEntity?

    /**
     * Bulk delete (bukan derived load-then-remove): dijalankan SEKETIKA ke DB, sehingga saat
     * rotasi token, DELETE lama sudah tuntas sebelum INSERT baru — kalau tidak, Hibernate
     * mengurutkan INSERT sebelum DELETE dalam satu transaksi dan melanggar UNIQUE(server_id).
     */
    @Modifying
    @Query("delete from VpnNodeTokenJpaEntity t where t.serverId = :serverId")
    fun deleteByServerId(@Param("serverId") serverId: UUID)
}
