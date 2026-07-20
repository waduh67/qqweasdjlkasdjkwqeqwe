package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.AssetStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "pon_port")
class PonPortJpaEntity(
    id: UUID,

    @Column(name = "olt_id", nullable = false, updatable = false)
    var oltId: UUID,

    @Column(nullable = false, length = 30)
    var label: String,

    @Column(length = 255)
    var description: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AssetStatus,
) : TenantAwareJpaEntity(id)
