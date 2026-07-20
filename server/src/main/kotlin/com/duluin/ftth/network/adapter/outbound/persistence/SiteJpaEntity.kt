package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.locationtech.jts.geom.Point
import java.util.UUID

@Entity
@Table(name = "site")
class SiteJpaEntity(
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
) : TenantAwareJpaEntity(id)
