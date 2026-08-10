package com.duluin.ftth.inbox.application.port.inbound

import com.duluin.ftth.inbox.domain.model.NotificationKind
import com.duluin.ftth.inbox.domain.model.NotificationSeverity
import java.time.Instant
import java.util.UUID

/**
 * Kotak masuk milik PENGGUNA YANG SEDANG LOGIN. Tak ada parameter "punya siapa" di mana
 * pun: audiens diambil dari sesi, jadi tak ada jalan memanggilnya untuk melihat lonceng
 * orang lain.
 */
interface NotificationInboxQuery {

    /** Isi lonceng + hitungan belum dibaca dalam satu panggilan (satu round-trip). */
    fun feed(unreadOnly: Boolean, limit: Int): InboxFeedView

    /**
     * Hanya angka di lencana. Dipisah dari [feed] karena inilah yang dipanggil berkala
     * selagi konsol terbuka — mengangkut daftar isinya tiap menit hanya untuk sebuah
     * angka itu pemborosan yang berlipat dengan jumlah operator yang online.
     */
    fun unreadCount(): Long
}

data class InboxFeedView(
    val unread: Long,
    val items: List<InboxNotificationView>,
)

data class InboxNotificationView(
    val id: UUID,
    val kind: NotificationKind,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    /** Rute konsol tujuan; null bila pemberitahuan ini tak menunjuk ke halaman tertentu. */
    val link: String?,
    val createdAt: Instant,
    /** Null = belum dibaca oleh pengguna ini. */
    val readAt: Instant?,
)
