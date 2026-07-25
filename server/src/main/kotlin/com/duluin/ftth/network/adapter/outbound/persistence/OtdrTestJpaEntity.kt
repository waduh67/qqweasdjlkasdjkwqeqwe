package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.CableEnd
import com.duluin.ftth.network.domain.model.OtdrEventType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Metadata satu hasil uji OTDR; titik perkiraannya dihitung dari geometri kabel saat dibaca. */
@Entity
@Table(name = "otdr_test")
class OtdrTestJpaEntity(
    id: UUID,

    @Column(name = "cable_id", nullable = false, updatable = false)
    var cableId: UUID,

    @Column(name = "distance_meters", nullable = false, updatable = false)
    var distanceMeters: Double,

    @Enumerated(EnumType.STRING)
    @Column(name = "measured_from", nullable = false, length = 10, updatable = false)
    var measuredFrom: CableEnd,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    var eventType: OtdrEventType,

    @Column(name = "loss_db", updatable = false)
    var lossDb: Double?,

    @Column(length = 500, updatable = false)
    var note: String?,

    @Column(name = "recorded_by", nullable = false, updatable = false)
    var recordedBy: UUID,

    @Column(name = "recorded_at", nullable = false, updatable = false)
    var recordedAt: Instant,
) : TenantAwareJpaEntity(id)
