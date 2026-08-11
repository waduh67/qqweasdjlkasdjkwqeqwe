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
@Table(name = "odf")
class OdfJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 40, updatable = false)
    var code: String,

    @Column(nullable = false, length = 150)
    var name: String,

    @Column(name = "site_id", nullable = false)
    var siteId: UUID,

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    var location: Point,

    @Column(name = "area_id")
    var areaId: UUID?,

    @Column(name = "port_count", nullable = false)
    var portCount: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AssetStatus,
) : TenantAwareJpaEntity(id)
