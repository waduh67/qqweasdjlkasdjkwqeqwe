package com.duluin.ftth.vpn.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface VpnServerJpaRepository : JpaRepository<VpnServerJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<VpnServerJpaEntity>
}

interface VpnPeerJpaRepository : JpaRepository<VpnPeerJpaEntity, UUID> {
    fun findByServerIdOrderByOverlayIpAsc(serverId: UUID): List<VpnPeerJpaEntity>
    fun existsByServerIdAndUsername(serverId: UUID, username: String): Boolean
    fun countByServerId(serverId: UUID): Long
}
