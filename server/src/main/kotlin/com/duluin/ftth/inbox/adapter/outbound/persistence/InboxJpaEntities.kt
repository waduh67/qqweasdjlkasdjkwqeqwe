package com.duluin.ftth.inbox.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inbox.domain.model.NotificationKind
import com.duluin.ftth.inbox.domain.model.NotificationSeverity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Satu peristiwa di lonceng operator. Seluruh kolomnya `updatable = false`: pemberitahuan
 * tak pernah disunting — yang berubah cuma "sudah dibaca siapa", dan itu tabel sebelah.
 */
@Entity
@Table(name = "inbox_notification")
class InboxNotificationJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    var kind: NotificationKind,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    var severity: NotificationSeverity,

    @Column(nullable = false, length = 150, updatable = false)
    var title: String,

    @Column(nullable = false, length = 500, updatable = false)
    var body: String,

    @Column(length = 200, updatable = false)
    var link: String?,

    /** Terisi = pemberitahuan pribadi; kosong = milik pemegang [requiredPermission]. */
    @Column(name = "target_user_id", updatable = false)
    var targetUserId: UUID?,

    @Column(name = "required_permission", length = 60, updatable = false)
    var requiredPermission: String?,

    @Column(name = "dedupe_key", nullable = false, length = 150, updatable = false)
    var dedupeKey: String,
) : TenantAwareJpaEntity(id)

/**
 * Penanda "sudah dibaca" milik SATU pengguna atas SATU pemberitahuan. Ketiadaan baris =
 * belum dibaca, jadi tak ada keadaan setengah jadi yang perlu dipelihara.
 */
@Entity
@Table(name = "inbox_notification_read")
class InboxNotificationReadJpaEntity(
    id: UUID,

    @Column(name = "notification_id", nullable = false, updatable = false)
    var notificationId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "read_at", nullable = false, updatable = false)
    var readAt: Instant,
) : TenantAwareJpaEntity(id)
