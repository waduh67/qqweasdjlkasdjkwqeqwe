package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.AssetStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.locationtech.jts.geom.Point
import java.util.UUID

@Entity
@Table(name = "odc")
class OdcJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 40, updatable = false)
    var code: String,

    @Column(nullable = false, length = 150)
    var name: String,

    @Column(length = 500)
    var address: String?,

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    var location: Point,

    @Column(name = "area_id")
    var areaId: UUID?,

    @Column(name = "pon_port_id")
    var ponPortId: UUID?,

    /** Disimpan sebagai label lapangan ("1:8"), bukan nama konstanta enum. */
    @Column(name = "splitter_ratio", nullable = false, length = 10)
    var splitterRatio: String,

    @Column(nullable = false)
    var capacity: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AssetStatus,
) : TenantAwareJpaEntity(id)
