package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** Izin — data referensi platform-level (bukan tenant-aware). */
@Entity
@Table(name = "permission")
class PermissionJpaEntity(
    id: UUID,

    @Column(nullable = false, unique = true, length = 120)
    var code: String,

    @Column(nullable = false, length = 40)
    var module: String,

    @Column(nullable = false, length = 40)
    var resource: String,

    @Column(nullable = false, length = 40)
    var action: String,

    @Column(length = 255)
    var description: String?,

    @Column(name = "platform_only", nullable = false)
    var platformOnly: Boolean,

    @Column(nullable = false)
    var active: Boolean,
) : BaseJpaEntity(id)
