package com.duluin.ftth.inbox.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inbox.application.port.outbound.OperatorNotificationRepository
import com.duluin.ftth.inbox.domain.model.InboxEntry
import com.duluin.ftth.inbox.domain.model.NotificationAudience
import com.duluin.ftth.inbox.domain.model.OperatorNotification
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class OperatorNotificationPersistenceAdapter(
    private val jpa: InboxNotificationJpaRepository,
    private val readJpa: InboxNotificationReadJpaRepository,
) : OperatorNotificationRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun saveIfAbsent(notification: OperatorNotification): Boolean {
        if (jpa.existsByDedupeKey(notification.dedupeKey)) return false
        return try {
            jpa.saveAndFlush(notification.toEntity())
            true
        } catch (ex: DataIntegrityViolationException) {
            // Dua proses berlomba menulis peristiwa yang sama: indeks unik `(tenant_id,
            // dedupe_key)` yang jadi wasitnya, dan yang kalah cukup mundur. Di-flush di sini
            // supaya bentrokannya ketahuan SEKARANG, bukan saat commit di tempat lain.
            log.debug("Pemberitahuan {} sudah ditulis proses lain", notification.dedupeKey, ex)
            false
        }
    }

    override fun findVisible(audience: NotificationAudience, unreadOnly: Boolean, limit: Int): List<InboxEntry> {
        var spec = visibleTo(audience)
        if (unreadOnly) spec = spec.and(unreadBy(audience.userId))
        val page = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
        val rows = jpa.findAll(spec, page).content
        if (rows.isEmpty()) return emptyList()

        // Sudah pasti belum dibaca kalau penyaringnya memang begitu — tak perlu tanya lagi.
        val readAt: Map<UUID, Instant> = if (unreadOnly) {
            emptyMap()
        } else {
            readJpa.findByUserIdAndNotificationIdIn(audience.userId, rows.map { it.id })
                .associate { it.notificationId to it.readAt }
        }
        return rows.map { InboxEntry(it.toDomain(), readAt[it.id]) }
    }

    override fun countUnread(audience: NotificationAudience): Long =
        jpa.count(visibleTo(audience).and(unreadBy(audience.userId)))

    override fun markRead(audience: NotificationAudience, ids: Collection<UUID>, at: Instant): Int {
        // Aturan "boleh lihat" dipinjam dari domain, bukan ditulis ulang di sini: kalau
        // keduanya berbeda sedikit saja, seseorang bisa menandai — dan dengan begitu
        // membuktikan keberadaan — pemberitahuan yang bukan haknya.
        val visible = jpa.findAllById(ids).filter { it.toDomain().visibleTo(audience) }
        return mark(audience.userId, visible.map { it.id }, at)
    }

    override fun markAllRead(audience: NotificationAudience, at: Instant): Int {
        val unread = jpa.findAll(visibleTo(audience).and(unreadBy(audience.userId))).map { it.id }
        return mark(audience.userId, unread, at)
    }

    override fun deleteOlderThan(cutoff: Instant): Int = jpa.deleteByCreatedAtBefore(cutoff).toInt()

    /** Menulis penanda baca hanya untuk yang belum bertanda — indeks uniknya sekali seumur hidup. */
    private fun mark(userId: UUID, notificationIds: List<UUID>, at: Instant): Int {
        if (notificationIds.isEmpty()) return 0
        val already = readJpa.findByUserIdAndNotificationIdIn(userId, notificationIds)
            .mapTo(HashSet()) { it.notificationId }
        val fresh = notificationIds.filterNot { it in already }
        if (fresh.isEmpty()) return 0
        readJpa.saveAll(
            fresh.map {
                InboxNotificationReadJpaEntity(
                    id = UuidV7.generate(),
                    notificationId = it,
                    userId = userId,
                    readAt = at,
                )
            },
        )
        return fresh.size
    }

    /**
     * Cermin SQL dari [OperatorNotification.visibleTo]: pemberitahuan pribadi milik yang
     * dituju, sisanya milik pemegang izin yang disebut (atau semua orang bila tak menyebut).
     */
    private fun visibleTo(audience: NotificationAudience) =
        Specification<InboxNotificationJpaEntity> { root, _, cb ->
            val target = root.get<UUID>("targetUserId")
            val permission = root.get<String>("requiredPermission")
            val holdsPermission = when {
                audience.bypassPermissions -> cb.conjunction()
                // `in` dengan daftar kosong bukan SQL yang sah; pengguna tanpa izin apa pun
                // memang tak berhak atas satu pun pemberitahuan bersama yang bersyarat.
                audience.permissions.isEmpty() -> cb.disjunction()
                else -> permission.`in`(audience.permissions)
            }
            cb.or(
                cb.equal(target, audience.userId),
                cb.and(cb.isNull(target), cb.or(cb.isNull(permission), holdsPermission)),
            )
        }

    /**
     * Belum ada penanda baca dari pengguna ini. `query` null hanya pada jalur yang tak
     * mendukung subquery; saat itu penyaring dilewatkan (semua ikut terhitung) — sama seperti
     * konvensi filter roster di module workorder.
     */
    private fun unreadBy(userId: UUID) =
        Specification<InboxNotificationJpaEntity> { root, query, cb ->
            if (query == null) {
                cb.conjunction()
            } else {
                val sub = query.subquery(UUID::class.java)
                val marker = sub.from(InboxNotificationReadJpaEntity::class.java)
                sub.select(marker.get("notificationId"))
                sub.where(
                    cb.equal(marker.get<UUID>("notificationId"), root.get<UUID>("id")),
                    cb.equal(marker.get<UUID>("userId"), userId),
                )
                cb.not(cb.exists(sub))
            }
        }

    private fun OperatorNotification.toEntity() = InboxNotificationJpaEntity(
        id = id,
        kind = kind,
        severity = severity,
        title = title,
        body = body,
        link = link,
        targetUserId = targetUserId,
        requiredPermission = requiredPermission,
        dedupeKey = dedupeKey,
    )
}

private fun InboxNotificationJpaEntity.toDomain() = OperatorNotification.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    kind = kind,
    severity = severity,
    title = title,
    body = body,
    link = link,
    targetUserId = targetUserId,
    requiredPermission = requiredPermission,
    dedupeKey = dedupeKey,
    createdAt = createdAt,
)
