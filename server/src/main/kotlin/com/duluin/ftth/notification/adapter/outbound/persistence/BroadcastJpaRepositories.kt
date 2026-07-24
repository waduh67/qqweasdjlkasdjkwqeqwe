package com.duluin.ftth.notification.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// findAll(Pageable) diwarisi dari JpaRepository — cukup untuk daftar riwayat ber-sort.
interface BroadcastJpaRepository : JpaRepository<BroadcastJpaEntity, UUID>

interface BroadcastRecipientJpaRepository : JpaRepository<BroadcastRecipientJpaEntity, UUID> {
    fun findByBroadcastIdOrderByAt(broadcastId: UUID): List<BroadcastRecipientJpaEntity>
}
