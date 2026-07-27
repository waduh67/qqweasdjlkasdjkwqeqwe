package com.duluin.ftth.vpn.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface VpnServerJpaRepository : JpaRepository<VpnServerJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<VpnServerJpaEntity>
}

interface VpnPeerJpaRepository : JpaRepository<VpnPeerJpaEntity, UUID> {
    fun findByServerIdOrderByOverlayIpAsc(serverId: UUID): List<VpnPeerJpaEntity>
    fun findByServerIdAndUsername(serverId: UUID, username: String): VpnPeerJpaEntity?
    fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean
    fun countByServerId(serverId: UUID): Long
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
