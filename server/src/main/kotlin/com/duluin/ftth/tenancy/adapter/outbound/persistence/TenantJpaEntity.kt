package com.duluin.ftth.tenancy.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.tenancy.TenantStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * JPA entity untuk tenant. Bukan tenant-aware (tabel platform-level, tanpa RLS).
 * Ini detail persistence — bukan model domain.
 */
@Entity
@Table(name = "tenant")
class TenantJpaEntity(
    id: UUID,

    @Column(nullable = false, unique = true, length = 63)
    var slug: String,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TenantStatus,
) : BaseJpaEntity(id)
