package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.iam.domain.model.UserStatus
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "app_user")
class UserJpaEntity(
    id: UUID,

    @Column(nullable = false)
    var email: String,

    @Column(nullable = false)
    var name: String,

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: UserStatus,

    @Column(name = "platform_admin", nullable = false)
    var platformAdmin: Boolean,

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_role", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "role_id", nullable = false)
    @BatchSize(size = 50)
    var roleIds: MutableSet<UUID> = mutableSetOf(),

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_area", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "area_id", nullable = false)
    @BatchSize(size = 50)
    var areaIds: MutableSet<UUID> = mutableSetOf(),

    /** Rahasia TOTP terenkripsi (AES-GCM). Terisi tapi `totpEnabledAt` null = pendaftaran menggantung. */
    @Column(name = "totp_secret")
    var totpSecret: String? = null,

    @Column(name = "totp_enabled_at")
    var totpEnabledAt: Instant? = null,

    /** Langkah waktu TOTP terakhir yang terpakai — penangkal pemakaian ulang kode. */
    @Column(name = "totp_last_step")
    var totpLastStep: Long? = null,
) : TenantAwareJpaEntity(id)
