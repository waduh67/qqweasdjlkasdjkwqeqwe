package com.duluin.ftth.inbox.application.service

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inbox.application.port.inbound.InboxFeedView
import com.duluin.ftth.inbox.application.port.inbound.InboxNotificationView
import com.duluin.ftth.inbox.application.port.inbound.MarkNotificationReadUseCase
import com.duluin.ftth.inbox.application.port.inbound.NotificationInboxQuery
import com.duluin.ftth.inbox.application.port.outbound.OperatorNotificationRepository
import com.duluin.ftth.inbox.domain.model.InboxEntry
import com.duluin.ftth.inbox.domain.model.NotificationAudience
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Kotak masuk operator: membaca lonceng dan menandainya terbaca.
 *
 * Audiens SELALU dirakit dari pengguna yang sedang login — tak pernah dari parameter —
 * sehingga tak ada permukaan API yang bisa dipakai mengintip lonceng orang lain.
 */
@Service
@Transactional(readOnly = true)
class NotificationInboxService(
    private val notifications: OperatorNotificationRepository,
    private val currentUser: CurrentUserProvider,
) : NotificationInboxQuery, MarkNotificationReadUseCase {

    override fun feed(unreadOnly: Boolean, limit: Int): InboxFeedView {
        val audience = audience()
        return InboxFeedView(
            unread = notifications.countUnread(audience),
            items = notifications.findVisible(audience, unreadOnly, limit.coerceIn(1, MAX_LIMIT)).map { it.toView() },
        )
    }

    override fun unreadCount(): Long = notifications.countUnread(audience())

    @Transactional
    override fun markRead(ids: Collection<UUID>): Int =
        if (ids.isEmpty()) 0 else notifications.markRead(audience(), ids, Instant.now())

    @Transactional
    override fun markAllRead(): Int = notifications.markAllRead(audience(), Instant.now())

    private fun audience(): NotificationAudience = with(currentUser.current()) {
        NotificationAudience(userId = userId, permissions = permissions, bypassPermissions = platformAdmin)
    }

    private fun InboxEntry.toView() = InboxNotificationView(
        id = notification.id,
        kind = notification.kind,
        severity = notification.severity,
        title = notification.title,
        body = notification.body,
        link = notification.link,
        createdAt = notification.createdAt,
        readAt = readAt,
    )

    private companion object {
        /**
         * Lonceng adalah daftar pendek yang dibaca sekilas, bukan arsip. Batas atas ini
         * menjaga satu permintaan iseng (`?limit=100000`) tak menarik seluruh riwayat tenant.
         */
        const val MAX_LIMIT = 100
    }
}
