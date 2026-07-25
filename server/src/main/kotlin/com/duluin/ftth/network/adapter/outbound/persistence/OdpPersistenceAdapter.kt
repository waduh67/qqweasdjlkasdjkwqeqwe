package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.domain.model.Odp
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OdpPersistenceAdapter(
    private val jpa: OdpJpaRepository,
) : OdpRepository {

    override fun save(odp: Odp): Odp {
        val entity = jpa.findById(odp.id).orElse(null)?.apply {
            name = odp.name
            address = odp.address
            location = Geometries.point(odp.location)
            areaId = odp.areaId
            odcId = odp.odcId
            splitterRatio = odp.splitterRatio.label
            capacity = odp.capacity
            status = odp.status
        } ?: OdpJpaEntity(
            id = odp.id,
            code = odp.code,
            name = odp.name,
            address = odp.address,
            location = Geometries.point(odp.location),
            areaId = odp.areaId,
            odcId = odp.odcId,
            splitterRatio = odp.splitterRatio.label,
            capacity = odp.capacity,
            status = odp.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Odp? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<Odp> = jpa.findAllById(ids).map { it.toDomain() }

    override fun findAllInAreas(areaIds: Set<UUID>?): List<Odp> =
        jpa.findAll(NetworkSpecifications.withinAreas<OdpJpaEntity>(areaIds)).map { it.toDomain() }

    override fun search(query: String, areaIds: Set<UUID>?, odcId: UUID?, pageRequest: PageRequest): Page<Odp> {
        val spec = NetworkSpecifications.textMatches<OdpJpaEntity>(query)
            .and(NetworkSpecifications.withinAreas(areaIds))
            .and(NetworkSpecifications.equals("odcId", odcId))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun findByOdcId(odcId: UUID): List<Odp> = jpa.findByOdcIdOrderByCode(odcId).map { it.toDomain() }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun countByOdcId(odcId: UUID): Long = jpa.countByOdcId(odcId)

    override fun countByOdcIds(odcIds: Set<UUID>): Map<UUID, Long> =
        if (odcIds.isEmpty()) emptyMap()
        else jpa.countGroupedByOdc(odcIds).associate { it.parentId to it.total }

    override fun findIdsByOdcIds(odcIds: Set<UUID>): Set<UUID> =
        if (odcIds.isEmpty()) emptySet() else jpa.findIdsByOdcIds(odcIds)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun OdpJpaEntity.toDomain(): Odp = Odp.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    odcId = odcId,
    splitterRatio = SplitterRatio.of(splitterRatio),
    capacity = capacity,
    status = status,
)
