package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Refresh token — SENGAJA bukan tenant-aware: lookup by hash terjadi sebelum
 * tenant context terbentuk (saat refresh/logout). Kolom tenant_id tetap disimpan
 * agar rotasi bisa memasang tenant context yang benar.
 */
@Entity
@Table(name = "refresh_token")
class RefreshTokenJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant?,
) : BaseJpaEntity(id)
