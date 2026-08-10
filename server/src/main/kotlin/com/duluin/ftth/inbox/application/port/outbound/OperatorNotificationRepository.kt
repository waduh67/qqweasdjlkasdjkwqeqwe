package com.duluin.ftth.inbox.application.port.outbound

import com.duluin.ftth.inbox.domain.model.InboxEntry
import com.duluin.ftth.inbox.domain.model.NotificationAudience
import com.duluin.ftth.inbox.domain.model.OperatorNotification
import java.time.Instant
import java.util.UUID

interface OperatorNotificationRepository {

    /**
     * Menyimpan bila kunci idempotennya belum pernah dipakai di tenant ini.
     *
     * Cek-lalu-simpan sengaja dibungkus jadi SATU operasi milik adapter, bukan dua panggilan
     * dari service: penjaga SLA dan korelasi insiden bisa berjalan bersamaan, dan indeks unik
     * `(tenant_id, dedupe_key)` adalah satu-satunya wasit yang benar di antara dua proses.
     *
     * @return false bila sudah pernah ada (bukan error — memang itu tujuannya).
     */
    fun saveIfAbsent(notification: OperatorNotification): Boolean

    fun findVisible(audience: NotificationAudience, unreadOnly: Boolean, limit: Int): List<InboxEntry>

    fun countUnread(audience: NotificationAudience): Long

    /** Hanya id yang benar-benar terlihat [audience] yang ditandai; sisanya diabaikan. */
    fun markRead(audience: NotificationAudience, ids: Collection<UUID>, at: Instant): Int

    fun markAllRead(audience: NotificationAudience, at: Instant): Int

    /** Membuang pemberitahuan yang lebih tua dari [cutoff] beserta penanda bacanya. */
    fun deleteOlderThan(cutoff: Instant): Int
}
