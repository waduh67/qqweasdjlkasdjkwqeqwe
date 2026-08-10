package com.duluin.ftth.inbox.adapter.inbound.web

import com.duluin.ftth.inbox.application.port.inbound.InboxFeedView
import com.duluin.ftth.inbox.application.port.inbound.MarkNotificationReadUseCase
import com.duluin.ftth.inbox.application.port.inbound.NotificationInboxQuery
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Lonceng pemberitahuan konsol — isinya milik PENGGUNA YANG SEDANG LOGIN.
 *
 * Sengaja tanpa `@PreAuthorize`: tak ada izin "boleh punya kotak masuk". Yang menentukan isi
 * justru izin yang sudah dipegang pengguna (dipakai sebagai penyaring audiens di dalam), jadi
 * seorang teknisi melihat tugasnya sendiri sementara supervisor melihat antrean bersama —
 * tanpa satu pun pintu tambahan yang perlu diberikan admin.
 */
@RestController
@RequestMapping("/api/inbox/notifications")
@Tag(name = "Inbox")
@SecurityRequirement(name = "bearer-jwt")
class NotificationInboxController(
    private val query: NotificationInboxQuery,
    private val markRead: MarkNotificationReadUseCase,
) {

    @GetMapping
    fun feed(
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "20") limit: Int,
    ): InboxFeedView = query.feed(unreadOnly, limit)

    /** Hanya angka lencana — inilah yang dijemput berkala oleh konsol yang sedang terbuka. */
    @GetMapping("/unread-count")
    fun unreadCount(): UnreadCountResponse = UnreadCountResponse(query.unreadCount())

    @PostMapping("/read")
    fun read(@RequestBody request: MarkReadRequest): MarkReadResponse =
        MarkReadResponse(markRead.markRead(request.ids))

    @PostMapping("/read-all")
    fun readAll(): MarkReadResponse = MarkReadResponse(markRead.markAllRead())
}

data class UnreadCountResponse(val unread: Long)

data class MarkReadRequest(val ids: List<UUID> = emptyList())

data class MarkReadResponse(val marked: Int)
