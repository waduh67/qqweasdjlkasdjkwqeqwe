package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * Indeks pre-auth email→tenant. SENGAJA bukan tenant-aware (extends [BaseJpaEntity],
 * BUKAN TenantAwareJpaEntity): lookup terjadi saat login SEBELUM tenant context ada —
 * server belum tahu tenant mana. Sama pola dengan [RefreshTokenJpaEntity].
 *
 * PK ([getId]) = id app_user (1:1). [emailLower] unik GLOBAL → 1 email = 1 tenant.
 */
@Entity
@Table(name = "user_directory")
class UserDirectoryJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "email_lower", nullable = false, unique = true, length = 255)
    var emailLower: String,
) : BaseJpaEntity(id)
