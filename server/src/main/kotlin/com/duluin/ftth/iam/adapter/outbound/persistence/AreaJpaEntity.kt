package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "area")
class AreaJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 40)
    var code: String,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "parent_id")
    var parentId: UUID?,
) : TenantAwareJpaEntity(id)
