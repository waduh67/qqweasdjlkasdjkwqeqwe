package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.CoreStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "cable_core")
class CableCoreJpaEntity(
    id: UUID,

    @Column(name = "cable_id", nullable = false, updatable = false)
    var cableId: UUID,

    @Column(name = "tube_number", nullable = false, updatable = false)
    var tubeNumber: Int,

    @Column(name = "core_number", nullable = false, updatable = false)
    var coreNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CoreStatus,

    @Column(length = 200)
    var note: String?,
) : TenantAwareJpaEntity(id)
