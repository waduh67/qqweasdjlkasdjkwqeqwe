package com.duluin.ftth.portal.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * Refresh token opaque portal, disimpan sebagai HASH (bukan nilai mentah) — kalau DB
 * bocor token tak bisa dipakai. Rotasi: setiap refresh, token lama dicabut dan
 * diterbitkan yang baru. Meniru `RefreshToken` operator, tapi menunjuk pelanggan
 * ([customerId]) bukan pengguna IAM.
 */
class PortalRefreshToken private constructor(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
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
        fun issue(tenantId: UUID, customerId: UUID, tokenHash: String, expiresAt: Instant): PortalRefreshToken =
            PortalRefreshToken(UuidV7.generate(), tenantId, customerId, tokenHash, expiresAt, null)

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID,
            tokenHash: String,
            expiresAt: Instant,
            revokedAt: Instant?,
        ): PortalRefreshToken = PortalRefreshToken(id, tenantId, customerId, tokenHash, expiresAt, revokedAt)
    }
}
