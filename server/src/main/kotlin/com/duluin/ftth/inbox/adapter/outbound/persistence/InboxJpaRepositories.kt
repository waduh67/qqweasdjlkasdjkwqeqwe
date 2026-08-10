package com.duluin.ftth.inbox.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant
import java.util.UUID

interface InboxNotificationJpaRepository :
    JpaRepository<InboxNotificationJpaEntity, UUID>,
    JpaSpecificationExecutor<InboxNotificationJpaEntity> {

    fun existsByDedupeKey(dedupeKey: String): Boolean

    /** Penanda baca ikut terhapus lewat `ON DELETE CASCADE` di sisi DB. */
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}

interface InboxNotificationReadJpaRepository : JpaRepository<InboxNotificationReadJpaEntity, UUID> {

    fun findByUserIdAndNotificationIdIn(userId: UUID, notificationIds: Collection<UUID>):
        List<InboxNotificationReadJpaEntity>
}
