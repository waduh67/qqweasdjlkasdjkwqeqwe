package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * Refresh token opaque yang disimpan sebagai HASH (bukan nilai mentah) — kalau DB
 * bocor, token tidak bisa dipakai. Rotasi: setiap refresh, token lama dicabut dan
 * diterbitkan yang baru.
 */
class RefreshToken private constructor(
    val id: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    revokedAt: Instant?,
) {
    var revokedAt: Instant? = revokedAt
        private set

    fun revoke(at: Instant = Instant.now()) {
        if (revokedAt == null) revokedAt = at
    }

    fun isActive(now: Instant = Instant.now()): Boolean =
        revokedAt == null && now.isBefore(expiresAt)

    companion object {
        fun issue(tenantId: UUID, userId: UUID, tokenHash: String, expiresAt: Instant): RefreshToken =
            RefreshToken(UuidV7.generate(), tenantId, userId, tokenHash, expiresAt, null)

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            userId: UUID,
            tokenHash: String,
            expiresAt: Instant,
            revokedAt: Instant?,
        ): RefreshToken = RefreshToken(id, tenantId, userId, tokenHash, expiresAt, revokedAt)
    }
}
