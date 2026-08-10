package com.duluin.ftth.inbox.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Jenis peristiwa yang layak menyalakan lonceng operator. Sengaja sedikit: lonceng yang
 * berbunyi untuk segala hal akan dimatikan orang, dan lonceng yang dimatikan tak memberi
 * tahu apa-apa. Yang masuk hanyalah peristiwa yang MENUNTUT TINDAKAN MANUSIA.
 */
enum class NotificationKind {
    /** Tiket bantuan melewati janji waktunya — seseorang harus menjawab/menuntaskan. */
    HELPDESK_SLA,

    /** Gangguan jaringan baru terkorelasi dari alarm — butuh keputusan penanganan. */
    INCIDENT_OPENED,

    /** Work order ditugaskan; yang ditugaskan perlu tahu tanpa harus memantau papan. */
    WORK_ORDER_ASSIGNED,
}

/** Seberapa keras pemberitahuan ini boleh menarik perhatian; menentukan warna di konsol. */
enum class NotificationSeverity { INFO, WARNING, CRITICAL }

/**
 * Siapa yang sedang membaca kotak masuk. Dirakit dari pengguna yang login, lalu dipakai
 * BAIK oleh [OperatorNotification.visibleTo] (aturan domain) MAUPUN oleh penyaring SQL di
 * adapter — keduanya harus berbunyi sama, jadi aturannya ditulis sekali di sini.
 *
 * [bypassPermissions] = platform admin: melewati semua pengecekan izin, persis seperti
 * `AuthenticatedUser.hasPermission`.
 */
data class NotificationAudience(
    val userId: UUID,
    val permissions: Set<String>,
    val bypassPermissions: Boolean,
) {
    fun holds(permission: String): Boolean = bypassPermissions || permission in permissions
}

/**
 * Satu pemberitahuan di kotak masuk operator.
 *
 * Audiensnya disebut dengan SALAH SATU dari dua cara, tak pernah dua-duanya:
 * [targetUserId] untuk pemberitahuan pribadi, atau [requiredPermission] untuk "siapa pun
 * yang berwenang menangani". Keduanya kosong berarti seluruh isi tenant boleh melihat.
 *
 * Penerima sengaja diputuskan saat DIBACA. Fan-out saat menulis (satu baris per calon
 * penerima) akan langsung usang begitu role seseorang berubah — lihat V86 untuk alasan
 * lengkapnya.
 */
class OperatorNotification private constructor(
    val id: UUID,
    val tenantId: UUID,
    val kind: NotificationKind,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    /** Rute konsol yang dituju saat diklik; null bila tak ada tujuan yang lebih spesifik. */
    val link: String?,
    val targetUserId: UUID?,
    val requiredPermission: String?,
    /** Penjaga idempoten: peristiwa yang sama tak boleh menumpuk di lonceng orang. */
    val dedupeKey: String,
    /**
     * Saat pemberitahuan MASUK kotak (bukan saat peristiwanya terjadi) — itulah urutan yang
     * dibaca manusia di lonceng. Nilai otoritatifnya kolom `created_at`; yang di sini
     * terpakai sebelum baris pertama kali disimpan.
     */
    val createdAt: Instant,
) {
    /**
     * Cermin Kotlin dari penyaring audiens di SQL (lihat `OperatorNotificationPersistenceAdapter`).
     * Dipakai pada jalur yang sudah memegang objeknya — mis. saat menandai terbaca, supaya
     * seseorang tak bisa menandai (dan dengan begitu mengintip keberadaan) pemberitahuan
     * yang bukan haknya hanya dengan menebak id.
     */
    fun visibleTo(audience: NotificationAudience): Boolean = when {
        targetUserId != null -> targetUserId == audience.userId
        requiredPermission != null -> audience.holds(requiredPermission)
        else -> true
    }

    companion object {
        const val MAX_TITLE = 150
        const val MAX_BODY = 500

        /** Ditujukan ke satu orang. */
        @Suppress("LongParameterList")
        fun personal(
            tenantId: UUID,
            kind: NotificationKind,
            severity: NotificationSeverity,
            title: String,
            body: String,
            link: String?,
            userId: UUID,
            dedupeKey: String,
        ): OperatorNotification = of(
            tenantId, kind, severity, title, body, link,
            targetUserId = userId, requiredPermission = null, dedupeKey = dedupeKey,
        )

        /** Ditujukan ke siapa pun pemegang [permission] — antrean bersama, bukan milik satu orang. */
        @Suppress("LongParameterList")
        fun forHolders(
            tenantId: UUID,
            kind: NotificationKind,
            severity: NotificationSeverity,
            title: String,
            body: String,
            link: String?,
            permission: String,
            dedupeKey: String,
        ): OperatorNotification = of(
            tenantId, kind, severity, title, body, link,
            targetUserId = null, requiredPermission = permission, dedupeKey = dedupeKey,
        )

        @Suppress("LongParameterList")
        private fun of(
            tenantId: UUID,
            kind: NotificationKind,
            severity: NotificationSeverity,
            title: String,
            body: String,
            link: String?,
            targetUserId: UUID?,
            requiredPermission: String?,
            dedupeKey: String,
        ): OperatorNotification {
            if (title.isBlank()) throw ValidationException("Judul pemberitahuan wajib diisi")
            if (dedupeKey.isBlank()) throw ValidationException("Kunci idempoten pemberitahuan wajib diisi")
            return OperatorNotification(
                id = UuidV7.generate(),
                tenantId = tenantId,
                kind = kind,
                severity = severity,
                title = title.take(MAX_TITLE),
                body = body.take(MAX_BODY),
                link = link,
                targetUserId = targetUserId,
                requiredPermission = requiredPermission,
                dedupeKey = dedupeKey,
                createdAt = Instant.now(),
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            kind: NotificationKind,
            severity: NotificationSeverity,
            title: String,
            body: String,
            link: String?,
            targetUserId: UUID?,
            requiredPermission: String?,
            dedupeKey: String,
            createdAt: Instant,
        ) = OperatorNotification(
            id, tenantId, kind, severity, title, body, link,
            targetUserId, requiredPermission, dedupeKey, createdAt,
        )
    }
}

/** Pemberitahuan beserta jawaban "sudah dibaca belum" untuk pembaca tertentu. */
data class InboxEntry(
    val notification: OperatorNotification,
    val readAt: Instant?,
) {
    val unread: Boolean get() = readAt == null
}
