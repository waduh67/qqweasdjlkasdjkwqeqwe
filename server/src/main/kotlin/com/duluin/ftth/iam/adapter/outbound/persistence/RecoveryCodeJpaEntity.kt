package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_recovery_code")
class RecoveryCodeJpaEntity(
    id: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    /** SHA-256 hex dari kode terbaca — kodenya sendiri tak pernah tersimpan di mana pun. */
    @Column(name = "code_hash", nullable = false, length = 64, updatable = false)
    val codeHash: String,

    @Column(name = "used_at")
    var usedAt: Instant? = null,
) : TenantAwareJpaEntity(id)
